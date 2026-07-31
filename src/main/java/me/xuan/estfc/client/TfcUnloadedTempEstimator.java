package me.xuan.estfc.client;

import me.xuan.estfc.Config;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 未加载区块的 TFC 温度修正——精确梯度 + 南北半球。
 * TFC {@code getInstantTemperature} 在 ChunkData.EMPTY 时将
 * avgSeaLevelTemp 固定为 10°C，导致未加载区块温度偏高→漏雪。
 *
 * <p>从已加载区块采样真实 avgSeaLevelTemp 并缓存（按绝对坐标），
 * 对目标位置取最近采样点的值做线性插值或梯度外推。
 * 缓存只增不删，同一位置的估算不随玩家移动变化。</p>
 *
 * 公式：{@code temp_REAL = temp_EMPTY - 10 + cached_avg(Z) + offset}
 */
public final class TfcUnloadedTempEstimator {

    /** 采样间隔 */
    private static final int SAMPLE_SPACING = 64;
    /** 两次采样之间的最小 tick 间隔 (100 tick ≈ 5 秒) */
    private static final long SAMPLE_INTERVAL_TICKS = 100;
    /** 停止频繁采样前所需的最少缓存条目数 */
    private static final int MIN_SAMPLES_BEFORE_EARLY_EXIT = 2;

    /**
     * Z → avgSeaLevelTemp 缓存（绝对坐标，永不过期，只增不删）。
     * 被 DH LOD Builder 线程池 + MapChecker 路径并发读写，必须是并发结构。
     */
    private static final ConcurrentSkipListMap<Integer, Float> zAvgTempCache = new ConcurrentSkipListMap<>();

    /** 上一次采样时的玩家 (X, Z) + tick，CAS 原子更新并选出唯一采样线程 */
    private record SampleMark(int x, int z, long tick) {}
    private static final AtomicReference<SampleMark> lastSample =
        new AtomicReference<>(new SampleMark(Integer.MAX_VALUE, Integer.MAX_VALUE, 0L));

    /** 缓存条目数——ConcurrentSkipListMap.size() 是 O(n)，节流热路径上不可用 */
    private static final AtomicInteger sampleCount = new AtomicInteger();

    private TfcUnloadedTempEstimator() {}

    /**
     * 获取修正后的 TFC 温度。对未加载区块用缓存的 avgSeaLevelTemp 替换 10°C 基线。
     */
    public static float getCorrectedTemperature(Level level, ClimateModel model, BlockPos pos) {
        float temp = model.getInstantTemperature(level, pos);

        if (!level.isLoaded(pos)) {
            float estAvgSea = estimateAvgSeaLevelTemp(level, model, pos);
            temp = temp - 10.0f + estAvgSea + Config.dhUnloadedTempOffset();
        }

        return temp;
    }

    /**
     * 估算指定位置的 avgSeaLevelTemp。
     * <ol>
     *   <li>缓存中有包围 targetZ 的上下界 → 线性插值</li>
     *   <li>缓存只有单侧界 → 取最近的采样值（误差有界，不会放大噪声）</li>
     *   <li>缓存空 → 退回 10°C</li>
     * </ol>
     */
    private static float estimateAvgSeaLevelTemp(Level level, ClimateModel model, BlockPos pos) {
        trySampleAlongZ(level, model);
        int targetZ = pos.getZ();

        if (zAvgTempCache.isEmpty()) return 10.0f;

        Map.Entry<Integer, Float> lower = zAvgTempCache.floorEntry(targetZ);
        Map.Entry<Integer, Float> upper = zAvgTempCache.ceilingEntry(targetZ);

        // 情况 1：上下界都有 → 线性插值（精确）
        if (lower != null && upper != null) {
            if (lower.getKey().equals(upper.getKey())) return lower.getValue();
            float frac = (float) (targetZ - lower.getKey()) / (upper.getKey() - lower.getKey());
            return lower.getValue() + frac * (upper.getValue() - lower.getValue());
        }

        // 情况 2：只有单侧界 → 取最近采样值
        // 不用梯度外推：TFC 温度场在 64 格跨度上有 ±3°C 的局部噪声，
        // 以此噪声级梯度外推到大距离会产生巨大误差（30°C+）。
        // 最近值误差有界（≤ 本地温度变幅 ≈ 5°C）。
        if (lower != null) return lower.getValue();
        if (upper != null) return upper.getValue();

        return 10.0f;
    }

    /**
     * 从玩家位置沿 Z 轴采样 avgSeaLevelTemp。
     * 缓存按绝对 Z 坐标存储，只增不删——同一 Z 的估算永远一致。
     */
    private static void trySampleAlongZ(Level level, ClimateModel model) {
        List<? extends Player> players;
        Player player;
        try {                                    // ClientLevel 的玩家列表由主线程 mutate
            players = level.players();
            if (players.isEmpty()) return;
            player = players.get(0);
            if (player == null) return;
        } catch (RuntimeException e) { return; } // 并发结构性变更 → 跳过本轮

        BlockPos playerPos = player.blockPosition();
        int px = playerPos.getX();
        int pz = playerPos.getZ();
        long tick = level.getGameTime();

        // 玩家移动足够远或足够久 → 采样
        SampleMark prev = lastSample.get();
        int moved = Math.abs(px - prev.x()) + Math.abs(pz - prev.z());
        if (moved < 64 && (tick - prev.tick()) < SAMPLE_INTERVAL_TICKS
                && sampleCount.get() >= MIN_SAMPLES_BEFORE_EARLY_EXIT) {
            return;
        }
        if (!lastSample.compareAndSet(prev, new SampleMark(px, pz, tick))) return;  // 别的线程抢到了

        int sampleRadius = Config.unloadedTempSampleRange();
        for (int dz = -sampleRadius; dz <= sampleRadius; dz += SAMPLE_SPACING) {
            int z = pz + dz;
            if (zAvgTempCache.containsKey(z)) continue;

            BlockPos samplePos = new BlockPos(px, 63, z);
            if (level.isLoaded(samplePos)) {
                try {
                    if (zAvgTempCache.putIfAbsent(z, model.getAverageTemperature(level, samplePos)) == null) {
                        sampleCount.incrementAndGet();
                    }
                } catch (Exception ignored) {}
            }
        }
    }
}
