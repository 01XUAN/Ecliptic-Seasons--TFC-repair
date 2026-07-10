package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import me.xuan.estfc.Config;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.TreeMap;

/**
 * 将 TFC 温度数据注入 ES 的雪线判断，使 DH/Voxy 远景正确覆雪。
 *
 * <h3>核心设计</h3>
 * ES 的 {@code shouldSnowAtBiome} 首先调用 {@code isAboveSnowLine(Level, Biome, BlockPos)}
 * 判断位置是否在雪线以上——如果是则直接返回 true，覆雪。
 * <p>
 * 本 Mixin 覆盖 {@code isAboveSnowLine(Level, Biome, BlockPos)}，
 * 用 TFC 瞬时温度替代 {@code BiomeClimateManager} 的数据包雪线高度。
 * 这样 DH 的 {@code DHTool.computeBaseColor → shouldSnowAtBiome → isAboveSnowLine}
 * 整条链路都不需要修改 ES 代码，只需提供正确的温度数据。
 *
 * <h3>为什么不用数据包？</h3>
 * TFC 温度是 (x, y, z, 日历) 的函数，雪线随纬度和时间动态变化。
 * {@code BiomeClimateManager.SNOW_LINE_MAP} 是静态 biome→高度 映射，
 * 无法捕捉位置和时间的双重动态性。在 {@code isAboveSnowLine} 中
 * 直接查询 TFC 温度是最精确的方案。
 *
 * <h3>未加载区块 (DH LOD)</h3>
 * 对于远距离未加载区块，TFC 的 {@code getInstantTemperature} 使用
 * {@code ChunkData.EMPTY}（avgSeaLevelTemp 恒为 10°C）。
 * 我们从已加载区块采样真实的 avgSeaLevelTemp 并沿 Z 轴插值修正：
 * <pre>
 *   temp_REAL = temp_EMPTY - 10 + avgSeaTemp_estimated(z)
 * </pre>
 *
 * <h3>与 shouldSnowAtBiome 的配合</h3>
 * 本 Mixin 只覆写 {@code isAboveSnowLine}，不碰 {@code shouldSnowAtBiome}。
 * ES 原生的 {@code shouldSnowAtBiome} 逻辑完整保留：
 * <ol>
 *   <li>{@code isAboveSnowLine} → TFC 温度判断（本 Mixin 提供）</li>
 *   <li>若不在雪线以上 → fallback 到 biome 雪深累积检查
 *       （由 {@code WeatherBridge.tickWeatherCycle} 驱动）</li>
 * </ol>
 * 两条路径都基于 TFC 数据，且互不干扰。
 */
@Mixin(value = MapChecker.class, remap = false)
public abstract class MapCheckerMixin {

    @Unique
    private static final Logger LOG = LoggerFactory.getLogger("estfc");

    @Unique
    private static boolean LOGGED_FIRST = false;

    @Unique
    private static int DEBUG_COUNT = 0;

    @Unique
    private static final int DEBUG_LIMIT = 50;

    // ========== Z → avgSeaLevelTemp 缓存 ==========

    /** 采样间隔（blocks），沿 Z 轴每隔此距离从已加载区块采样一次温度 */
    @Unique
    private static final int SAMPLE_SPACING = 64;

    /** 采样范围（blocks），从玩家位置向 ±Z 方向各采样此距离 */
    @Unique
    private static final int SAMPLE_RANGE = 512;

    /** 缓存过期时间 (ms)，超过此时间后重新采样 */
    @Unique
    private static final long CACHE_TTL_MS = 2000;

    /** Z 坐标 → avgSeaLevelTemp（海平面年均温度） */
    @Unique
    private static final TreeMap<Integer, Float> zAvgTempCache = new TreeMap<>();

    /** 上次缓存更新时间 */
    @Unique
    private static long lastCacheUpdateMs = 0;

    /** 上次更新时的玩家 Z（用于检测是否移动到不同气候带） */
    @Unique
    private static int lastPlayerZ = Integer.MAX_VALUE;

    // ========== 核心注入：isAboveSnowLine ==========

    /**
     * 覆盖 {@code MapChecker.isAboveSnowLine(Level, Biome, BlockPos)}，
     * 用 TFC 瞬时温度判断位置是否在雪线以上。
     *
     * <p>此方法是 {@code shouldSnowAtBiome} 的第一道检查，
     * 也是 DH/Voxy 远景覆雪的关键入口。</p>
     *
     * @param level 当前世界（客户端或服务端）
     * @param biome 群系（ES 从 DH LOD 数据还原）
     * @param pos   要检查的方块位置
     * @param cir   取消回调，设为 true=在雪线以上应覆雪
     */
    @Inject(method = "isAboveSnowLine", at = @At("HEAD"), cancellable = true)
    private static void estfc$isAboveSnowLine(
        Level level, Biome biome, BlockPos pos,
        CallbackInfoReturnable<Boolean> cir) {

        try {
            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return; // 非 TFC 世界，回退到 ES 原生

            boolean loaded = level.isLoaded(pos);
            float temp;

            if (loaded) {
                temp = model.getInstantTemperature(level, pos);
            } else {
                // 未加载区块（DH/Voxy 远景）：修正 ChunkData.EMPTY 的虚假 10°C
                // temp_EMPTY = 10 + monthEffect(z) + dailyEffect(z) + elevAdjustment(y)
                // temp_REAL  = avg(z) + monthEffect(z) + dailyEffect(z) + elevAdjustment(y)
                // → temp_REAL = temp_EMPTY - 10 + avg(z)
                float tempEmpty = model.getInstantTemperature(level, pos);
                float estAvgSea = estimateAvgSeaLevelTemp(level, model, pos.getZ());
                temp = tempEmpty - 10.0f + estAvgSea + Config.dhUnloadedTempOffset();
            }

            float threshold = Config.snowAppearanceTemp();
            boolean aboveSnowLine = temp <= threshold;
            cir.setReturnValue(aboveSnowLine);

            debugLog(pos, loaded, temp, threshold, aboveSnowLine);
        } catch (Exception e) {
            if (!LOGGED_FIRST) {
                LOGGED_FIRST = true;
                LOG.warn("[estfc] TFC unavailable for snow line, falling back to ES native", e);
            }
        }
    }

    // ========== avgSeaLevelTemp 估算 ==========

    /**
     * 估算指定 Z 坐标处的海平面年均温度。
     * 从已加载区块在不同 Z 坐标采样真实 ChunkData 中的 avgSeaLevelTemp，
     * 建立 Z→温度映射，对未加载 Z 坐标进行线性插值。
     */
    @Unique
    private static float estimateAvgSeaLevelTemp(Level level, ClimateModel model, int targetZ) {
        long now = System.currentTimeMillis();
        boolean needUpdate = zAvgTempCache.isEmpty()
            || (now - lastCacheUpdateMs) > CACHE_TTL_MS;

        // 玩家移动到不同气候带时立即刷新
        if (!level.players().isEmpty()) {
            int playerZ = level.players().getFirst().blockPosition().getZ();
            if (Math.abs(playerZ - lastPlayerZ) > 128) {
                needUpdate = true;
            }
        }

        if (needUpdate) {
            updateAvgTempCache(level, model);
            lastCacheUpdateMs = now;
            if (!level.players().isEmpty()) {
                lastPlayerZ = level.players().getFirst().blockPosition().getZ();
            }
        }

        if (zAvgTempCache.isEmpty()) {
            return 10.0f; // 无可用数据，退回 EMPTY 默认值
        }

        Map.Entry<Integer, Float> lower = zAvgTempCache.floorEntry(targetZ);
        Map.Entry<Integer, Float> upper = zAvgTempCache.ceilingEntry(targetZ);

        if (lower != null && upper != null) {
            if (lower.getKey().equals(upper.getKey())) {
                return lower.getValue(); // 精确命中
            }
            // 线性插值
            float frac = (float) (targetZ - lower.getKey()) / (upper.getKey() - lower.getKey());
            return lower.getValue() + frac * (upper.getValue() - lower.getValue());
        }

        // 外推：目标在所有采样点之外
        if (lower != null) return lower.getValue();
        if (upper != null) return upper.getValue();
        return 10.0f;
    }

    /**
     * 从已加载区块更新 Z→avgSeaLevelTemp 缓存。
     * 在玩家所在 X 坐标沿 Z 轴每隔 {@link #SAMPLE_SPACING} blocks 采样一次。
     * 采样 Y=64（海平面）处 {@link ClimateModel#getAverageTemperature}，
     * 该值即为 avgSeaLevelTemp（无海拔修正干扰）。
     */
    @Unique
    private static void updateAvgTempCache(Level level, ClimateModel model) {
        zAvgTempCache.clear();

        if (level.players().isEmpty()) return;

        var player = level.players().getFirst();
        int playerX = player.blockPosition().getX();
        int playerZ = player.blockPosition().getZ();

        int sampleCount = 0;
        for (int dz = -SAMPLE_RANGE; dz <= SAMPLE_RANGE; dz += SAMPLE_SPACING) {
            int z = playerZ + dz;
            BlockPos samplePos = new BlockPos(playerX, 64, z); // y=64 = SEA_LEVEL

            if (level.isLoaded(samplePos)) {
                try {
                    // getAverageTemperature 在 y=SEA_LEVEL 时 = avgSeaLevelTemp
                    float avgSeaTemp = model.getAverageTemperature(level, samplePos);
                    zAvgTempCache.put(z, avgSeaTemp);
                    sampleCount++;
                } catch (Exception ignored) {}
            }
        }

        if (!LOGGED_FIRST && sampleCount > 0) {
            LOGGED_FIRST = true;
            var first = zAvgTempCache.firstEntry();
            var last = zAvgTempCache.lastEntry();
            LOG.info("[estfc:zCache] Built Z→avgSeaTemp cache: {} samples, Z range [{},{}], temp range [{},{}]",
                sampleCount, first.getKey(), last.getKey(),
                String.format("%.1f", first.getValue()), String.format("%.1f", last.getValue()));
        }
    }

    // ========== 调试 ==========

    @Unique
    private static void debugLog(BlockPos pos, boolean loaded, float temp, float threshold, boolean snow) {
        if (DEBUG_COUNT >= DEBUG_LIMIT) return;
        DEBUG_COUNT++;
        LOG.info("[estfc:snowLine] #{} ({},{},{}) loaded={} temp={} threshold={} -> {}",
            DEBUG_COUNT, pos.getX(), pos.getY(), pos.getZ(), loaded,
            String.format("%.1f", temp), String.format("%.1f", threshold), snow);
    }
}
