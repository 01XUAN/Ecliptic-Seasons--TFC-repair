package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import me.xuan.estfc.Config;
import me.xuan.estfc.WeatherBridge;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
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
 * 客户端 shouldSnowAtBiome — DH/Voxy 远景入口。
 *
 * <h3>核心设计</h3>
 * TFC 温度公式（OverworldClimateModel.getInstantTemperature）：
 * <pre>
 *   temp = avgSeaLevelTemp(pos)          ← ChunkData 提供，EMPTY=10°C
 *        + monthlyTemperature(pos.z)     ← 只依赖 Z 坐标 ✓
 *        + dailyTemperature(pos.z, cal)  ← 只依赖 Z+日历 ✓
 *        + elevationAdjustment(pos.y)    ← 只依赖 Y 坐标 ✓
 * </pre>
 *
 * <p>{@code getInstantTemperature} 即使使用 ChunkData.EMPTY，其
 * monthly/daily/elevation 分量仍然<b>完全正确</b>。唯一错误的是
 * avgSeaLevelTemp（恒为 10°C）。</p>
 *
 * <h3>修复策略</h3>
 * 从已加载区块采样不同 Z 坐标的 avgSeaLevelTemp，建立 Z→温度缓存，
 * 对未加载区块按目标 Z 坐标插值估算，替换掉错误的 10°C。
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

    // ========== 核心注入 ==========

    /**
     * 拦截客户端 shouldSnowAtBiome，由 TFC 温度直接决策。
     * 服务端走 ES 原生（群系雪深由 WeatherBridge.tickWeatherCycle 驱动）。
     */
    @Inject(method = "shouldSnowAtBiome", at = @At("HEAD"), cancellable = true)
    private static void estfc$shouldSnowAtBiome(
        Level level, Biome biome, BlockState state, RandomSource random, long seed, BlockPos mcPos,
        CallbackInfoReturnable<Boolean> cir) {

        // 服务端：ES 原生
        if (level instanceof ServerLevel) return;

        try {
            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return;

            boolean loaded = level.isLoaded(mcPos);
            float temp = model.getInstantTemperature(level, mcPos);

            // 未加载区块：替换错误的 avgSeaLevelTemp
            // temp_EMPTY = 10 + monthEffect(z) + dailyEffect(z) + elevEffect(y)
            // temp_REAL  = avg(z) + monthEffect(z) + dailyEffect(z) + elevEffect(y)
            // → temp_REAL = temp_EMPTY - 10 + avg(z)
            if (!loaded) {
                float estAvgSea = estimateAvgSeaLevelTemp(level, model, mcPos.getZ());
                float estTemp = temp - 10.0f + estAvgSea + Config.dhUnloadedTempOffset();
                temp = estTemp;
            }

            int depth = WeatherBridge.tempToSnowDepth(temp);

            // 远景（未加载区块）不用 seed%100 抖动，纯净基于温度决定覆雪。
            // 原因：DH 在 LOD 渲染时每个 LOD 块只调用一次 shouldSnowAtBiome，
            // 同一个 LOD 块内的所有真实方块共享同一个 seed → 产生 64×64 网格
            // 和噪声曲线。远距离尺度下温度等高线已经足够自然。
            // 近场（已加载区块）保持 per-block seed 抖动，与 SnowyMapChecker 一致。
            boolean snow;
            if (loaded) {
                snow = depth > Math.abs(seed % 100);
            } else {
                snow = depth > 0;
            }
            cir.setReturnValue(snow);

            debugLog(mcPos, loaded, temp, depth, snow);
        } catch (Exception e) {
            if (!LOGGED_FIRST) {
                LOGGED_FIRST = true;
                LOG.warn("[estfc] TFC unavailable on client, falling back to ES native snow", e);
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
        // 检查是否需要更新缓存
        long now = System.currentTimeMillis();
        boolean needUpdate = zAvgTempCache.isEmpty()
            || (now - lastCacheUpdateMs) > CACHE_TTL_MS;

        // 如果玩家移动到不同气候带，立即更新
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

        // 从缓存中按 Z 坐标插值
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
    private static void debugLog(BlockPos pos, boolean loaded, float temp, int depth, boolean snow) {
        if (DEBUG_COUNT >= DEBUG_LIMIT) return;
        DEBUG_COUNT++;
        LOG.info("[estfc:shouldSnow] #{} ({},{},{}) loaded={} temp={} depth={} -> {}",
            DEBUG_COUNT, pos.getX(), pos.getY(), pos.getZ(), loaded, temp, depth, snow);
    }
}
