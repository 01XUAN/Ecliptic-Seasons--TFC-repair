package me.xuan.estfc.client;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockColorOverrideEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockStateWrapperCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.xuan.estfc.Config;
import me.xuan.estfc.weather.ClimateQuery;
import me.xuan.estfc.weather.SnowDepthRules;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.calendar.ICalendar;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.OverworldClimateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DH 远景覆雪（LOD 颜色覆盖，客户端）。
 * 挂在 {@code DhApiBlockColorOverrideEvent}：每数据点触发一次，列内自上而下（首事件即表面）。
 * 温度用区域气候点（avgSeaLevelTemp + 月温 − 区域海拔，128 格平滑场）——事件 y 非表面高度，
 * 逐 quad 采样会产生网格。同一列只处理表面数据点（首事件）。
 * 前置条件：ES 的 DistantHorizonsWinterLOD=false（否则其 @WrapOperation 短路本事件）。
 */
public final class DhSnowService {

    private static final Logger LOGGER = LoggerFactory.getLogger("estfc");

    /** 拿不到 dataSource 细节级别时的保守回退（偏细） */
    private static final int FALLBACK_DETAIL_LEVEL = 3;
    /** 雪色 RGB：与 ES DHTool 同款（DH 雪方块烘焙色 0xFFF9FEFE） */
    private static final int SNOW_COLOR_R = 0xF9, SNOW_COLOR_G = 0xFE, SNOW_COLOR_B = 0xFE;
    /** 缓存上限（满则清空重算） */
    private static final int CACHE_CAP = 32768;

    private record BiomeDepthEntry(long tick, int depth) {}
    private static final ConcurrentHashMap<String, BiomeDepthEntry> BIOME_DEPTH_CACHE = new ConcurrentHashMap<>();

    /** 列状态：key=列(level,x,z)，同一列所有数据点共享 (x,z)，首事件=表面 */
    /**
     * 列状态：key=列(level,x,z)。首个事件=表面；firstBottom 用于跳过同列更低的数据点，
     * 等于首个底部时视为新一轮 LOD 构建，重算温度（日温）但复用缓存的地表高度。
     */
    private record ColumnEntry(long day, int biomeDepth, int firstBottom, int surfaceTop) {}
    private static final ConcurrentHashMap<Long, ColumnEntry> COLUMN_CACHE = new ConcurrentHashMap<>();

    private static WeakReference<Level> boundLevel = new WeakReference<>(null);
    private static volatile boolean registered = false;

    private DhSnowService() {}

    /** 由 FMLClientSetupEvent 绑定（客户端，早于世界加载）。幂等。 */
    public static void init() {
        if (registered) return;
        registered = true;

        try {
            DhApi.events.bind(DhApiBlockStateWrapperCreatedEvent.class, new DhApiBlockStateWrapperCreatedEvent() {
                @Override
                public void blockStateWrapperCreated(DhApiEventParam<EventParam> event) {
                    event.value.setAllowApiColorOverride(true);
                }
            });
            DhApi.events.bind(DhApiBlockColorOverrideEvent.class, new DhApiBlockColorOverrideEvent() {
                @Override
                public void onBlockColorOverridden(DhApiEventParam<EventParam> event) {
                    try {
                        maybeOverride(event.value);
                    } catch (Throwable ignored) {
                        // 事件跑在 DH LOD 构建线程，异常/错误逃逸都会破坏对方流水线
                    }
                }
            });
            LOGGER.info("[estfc] DH snow color override registered");
        } catch (Throwable e) {
            // DH 缺失/版本不匹配时 Error 也可能抛出（NoClassDefFoundError），必须兜住
            LOGGER.warn("[estfc] DH API bind failed: {}", e.toString());
        }

        disableEsDhCompat();
    }

    private static void maybeOverride(DhApiBlockColorOverrideEvent.EventParam param) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        bind(level);
        if (ClimateQuery.getModelIfActive(level) == null) return;

        IDhApiBlockStateWrapper block = param.getBlockStateWrapper();
        if (block == null || block.isAir() || block.isLiquid()) return;

        int biomeDepth = biomeDepth(level, param.getBiomeWrapper());

        int levelIndex = dataDetailLevel(param.getDataSource());
        int x = param.getBlockPosX();
        int z = param.getBlockPosZ();
        long day = Calendars.get(level).getCalendarTicks() / ICalendar.TICKS_IN_DAY;
        long columnKey = columnKey(levelIndex, x >> levelIndex, z >> levelIndex);
        int eventBottom = param.getBlockPosY() - param.getLevelWrapper().getMinHeight();

        ColumnEntry col = COLUMN_CACHE.get(columnKey);
        boolean cacheValid = col != null && col.day() == day && col.biomeDepth() == biomeDepth;
        int cachedSurfaceY = Integer.MIN_VALUE;
        if (cacheValid) {
            if (eventBottom > col.firstBottom()) return;
            cachedSurfaceY = col.surfaceTop();
        }

        // 首事件 = 表面数据点：一次采样温度+区域高度，判定覆雪
        RegionClimateCache.Sample sample = RegionClimateCache.sampleClimate(x, z);
        if (sample == null) {
            // 区域数据未到：用已同步的群系覆盖率兜底，避免整块 LOD 烘焙成 0%
            if (biomeDepth > 0 && SnowDepthRules.shouldSnow(biomeDepth, Mth.getSeed(x, param.getBlockPosY(), z))) {
                param.setColor(SNOW_COLOR_R, SNOW_COLOR_G, SNOW_COLOR_B);
            }
            return;
        }

        int surfaceY = cachedSurfaceY;
        if (surfaceY == Integer.MIN_VALUE) {
            surfaceY = exactSurfaceY(param, eventBottom);
        }
        int resolvedY = surfaceY != Integer.MIN_VALUE ? surfaceY : (int) sample.height();

        Float temp = DistantTemperature.distantTemperature(level, sample, resolvedY);
        if (temp == null) return;

        COLUMN_CACHE.put(columnKey, new ColumnEntry(day, biomeDepth, eventBottom, resolvedY));
        if (COLUMN_CACHE.size() > CACHE_CAP) COLUMN_CACHE.clear();

        int coverage = biomeDepth;
        if (coverage <= 0) {
            coverage = SnowDepthRules.climateSeed(temp, sample.rainfall());
        }
        int depth = SnowDepthRules.depthFromCoverage(temp, coverage, sample.rainfall());
        if (depth > 0 && SnowDepthRules.shouldSnow(depth, Mth.getSeed(x, resolvedY, z))) {
            param.setColor(SNOW_COLOR_R, SNOW_COLOR_G, SNOW_COLOR_B);
        }
    }

    private static int dataDetailLevel(IDhApiFullDataSource dataSource) {
        if (dataSource instanceof FullDataSourceV2 fullDataSource) {
            return Math.max(0, fullDataSource.getDataDetailLevel());
        }
        return FALLBACK_DETAIL_LEVEL;
    }

    /**
     * 从 DH 数据源反推该列真实地表高度。
     * 事件自带的 y 是 merged run 底部，不是地表；这里取列内最高 data point 的 topY。
     * 失败时返回 Integer.MIN_VALUE，回退 128 格区域高度。
     */
    private static int exactSurfaceY(DhApiBlockColorOverrideEvent.EventParam param, int eventBottom) {
        try {
            if (!(param.getDataSource() instanceof FullDataSourceV2 dataSource)) {
                return Integer.MIN_VALUE;
            }

            int detail = dataDetailLevel(dataSource);
            int cell = 1 << detail;
            int width = dataSource.getWidthInDataColumns();
            long pos = dataSource.getPos();
            int minX = DhSectionPos.getCenterBlockPosX(pos) - (width * cell) / 2;
            int minZ = DhSectionPos.getCenterBlockPosZ(pos) - (width * cell) / 2;
            int relX = (param.getBlockPosX() - minX) / cell;
            int relZ = (param.getBlockPosZ() - minZ) / cell;
            if (relX < 0 || relX >= width || relZ < 0 || relZ >= width) {
                return Integer.MIN_VALUE;
            }

            LongArrayList column = dataSource.tryGetColumnAtRelPos(relX, relZ);
            if (column == null) return Integer.MIN_VALUE;

            int minY = param.getLevelWrapper().getMinHeight();
            int eventTop = Integer.MIN_VALUE;
            int top = Integer.MIN_VALUE;
            for (int i = 0; i < column.size(); i++) {
                long point = column.getLong(i);
                if (point == FullDataPointUtil.EMPTY_DATA_POINT) continue;
                try {
                    if (dataSource.mapping.getBlockStateWrapper(FullDataPointUtil.getId(point)).isAir()) {
                        continue;
                    }
                } catch (RuntimeException ignored) {
                    // 父级/旧版列可能带当前 mapping 不认识的 id，跳过该数据点而不是整列回退
                    continue;
                }

                int bottom = FullDataPointUtil.getBottomY(point);
                int pointTop = bottom + FullDataPointUtil.getHeight(point) + minY;
                top = Math.max(top, pointTop);
                if (bottom == eventBottom) eventTop = pointTop;
            }
            return eventTop != Integer.MIN_VALUE ? eventTop : top;
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    /** 列 key：level(3bit) + x>>level(25bit) + z>>level(25bit)。 */
    private static long columnKey(int levelIndex, int colX, int colZ) {
        return ((long) levelIndex << 57)
            | (((long) colX & 0x1FFFFFFL) << 31)
            | (((long) colZ & 0x1FFFFFFL) << 6);
    }

    /** biome 雪深（TTL 缓存；用 wrapper.getName() 作 key，命中时不碰注册表）。 */
    private static int biomeDepth(Level level, IDhApiBiomeWrapper wrapper) {
        if (wrapper == null) return 0;
        String cacheKey = wrapper.getName();

        long tick = level.getGameTime();
        long ttl = Config.biomeSyncIntervalTicks() * 2L;
        BiomeDepthEntry entry = BIOME_DEPTH_CACHE.get(cacheKey);
        if (entry != null && tick - entry.tick() < ttl) return entry.depth();

        Biome biome = unwrapBiome(wrapper);
        if (biome == null) return 0;

        int depth = ClimateQuery.getBiomeDepth(level, biome);
        BIOME_DEPTH_CACHE.put(cacheKey, new BiomeDepthEntry(tick, depth));
        return depth;
    }

    /** 从 DH 的 API 包装解出底层 Biome。 */
    private static Biome unwrapBiome(IDhApiBiomeWrapper wrapper) {
        Object wrapped = wrapper.getWrappedMcObject();
        if (wrapped instanceof Holder<?> holder && holder.value() instanceof Biome biome) return biome;
        return wrapped instanceof Biome biome ? biome : null;
    }

    /** 世界/维度切换时清空列缓存（列 key 不含维度，防跨世界误判）。 */
    private static void bind(Level level) {
        if (boundLevel.get() == level) return;
        COLUMN_CACHE.clear();
        BIOME_DEPTH_CACHE.clear();
        boundLevel = new WeakReference<>(level);
    }

    /**
     * 关掉 ES 自带的 DH 兼容（反射读写 ES 配置）。
     *
     * <p>它开启时会短路 DH 的 {@code getBlockColor}，本类的事件根本收不到；而且它经
     * {@code MapChecker.shouldSnowAtBiome} 决策时传的是 DH 数据点的 y（粗级别 = 列内最高点、
     * 细级别 = 顶部 run 底部，级别相关）→ 远景网格/斑驳。关掉后 DH 回到本类的区域高度路径。</p>
     */
    private static void disableEsDhCompat() {
        try {
            Class<?> compatClass = Class.forName("com.teamtea.eclipticseasons.compat.CompatModule$CommonConfig");
            var dhField = compatClass.getDeclaredField("DistantHorizonsWinterLOD");
            dhField.setAccessible(true);
            Object value = dhField.get(null);
            if (value instanceof net.minecraftforge.common.ForgeConfigSpec.BooleanValue boolValue && boolValue.get()) {
                boolValue.set(false);
                LOGGER.info("[estfc] ES DistantHorizonsWinterLOD forced off in memory; "
                    + "set DistantHorizonsWinterLOD=false in config/eclipticseasons-common.toml to persist");
            }
        } catch (Throwable ignored) {
            // ES 无此配置项（DH 未加载时）→ 无需处理
        }
    }
}
