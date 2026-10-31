package me.xuan.estfc.client;

import me.xuan.estfc.Config;
import me.xuan.estfc.weather.ClimateQuery;
import me.xuan.estfc.net.RegionClimatePayloads;
import me.xuan.estfc.net.RegionClimatePayloads.RegionClimateRequest;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.OverworldClimateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区域气候点（avgSeaLevelTemp + 表面高度，128 格场）客户端缓存。
 * 被 DH/voxy worker 并发读；请求集合由客户端 tick 线程 drain 发包。
 */
public final class RegionClimateCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("estfc");

    /** 缓存上限（满则清空重算，数据静态可重取） */
    private static final int REGION_CACHE_CAP = 65536;
    /** 待请求集合上限 */
    private static final int PENDING_CAP = 32768;
    /** 自检周期（tick） */
    private static final int SELF_CHECK_INTERVAL_TICKS = 100;
    /** 预取环重扫间隔（tick） */
    private static final int PREFETCH_RESCAN_TICKS = 20;

    /** 一次采样结果：温度 + 表面高度 + 年均降水量（双线性）。 */
    public record Sample(int x, int z, float temp, float height, float rainfall) {}

    private record RegionPoint(float temp, float height, float rainfall) {}

    private static final ConcurrentHashMap<Long, RegionPoint> REGION_POINTS = new ConcurrentHashMap<>();

    /** 缺失点请求集合（去重；drain 时缓存命中自动跳过） */
    private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();

    /** 绑定的客户端世界——换世界/维度时清空全部缓存 */
    private static WeakReference<Level> boundLevel = new WeakReference<>(null);

    /** 上次预取环扫描的玩家网格 + tick（玩家未移动时不重复扫） */
    private static int lastScanGridX = Integer.MAX_VALUE, lastScanGridZ = Integer.MAX_VALUE;
    private static long lastScanTick = Long.MIN_VALUE;

    private RegionClimateCache() {}

    /** 双线性插值（温度 + 高度一次遍历 4 角）；数据缺失返回 null。 */
    @Nullable
    public static Sample sampleClimate(int x, int z) {
        int gx = x >> 7;
        int gz = z >> 7;
        RegionPoint v00 = point(gx, gz);
        RegionPoint v01 = point(gx, gz + 1);
        RegionPoint v10 = point(gx + 1, gz);
        RegionPoint v11 = point(gx + 1, gz + 1);
        if (v00 == null || v01 == null || v10 == null || v11 == null) {
            return sampleNearest(x, z);
        }

        float deltaX = (x & 127) / 128f;
        float deltaZ = (z & 127) / 128f;
        return new Sample(x, z,
            lerp4(v00.temp, v01.temp, v10.temp, v11.temp, deltaX, deltaZ),
            lerp4(v00.height, v01.height, v10.height, v11.height, deltaX, deltaZ),
            lerp4(v00.rainfall, v01.rainfall, v10.rainfall, v11.rainfall, deltaX, deltaZ));
    }

    /** 四角缺失/请求未回时用最近的已缓存区域点，避免 Voxy 直接落到 biomeDepth=1。 */
    @Nullable
    private static Sample sampleNearest(int x, int z) {
        int gx = x >> 7;
        int gz = z >> 7;
        RegionPoint best = null;
        int bestGridX = 0;
        int bestGridZ = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int radius = 0; radius <= 2 && best == null; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    RegionPoint point = REGION_POINTS.get(RegionClimatePayloads.gridKey(gx + dx, gz + dz));
                    if (point == null) continue;
                    int dist = Math.abs((gx + dx) * 128 + 64 - x) + Math.abs((gz + dz) * 128 + 64 - z);
                    if (dist < bestDist) {
                        best = point;
                        bestGridX = gx + dx;
                        bestGridZ = gz + dz;
                        bestDist = dist;
                    }
                }
            }
        }
        return best == null ? null : new Sample(x, z, best.temp(), best.height(), best.rainfall());
    }

    /** 仅温度（自检/月温基准用）。 */
    public static float avgSeaLevelTemp(int x, int z) {
        Sample sample = sampleClimate(x, z);
        return sample == null ? Float.NaN : sample.temp();
    }

    private static float lerp4(float v00, float v01, float v10, float v11, float deltaX, float deltaZ) {
        float value0 = Mth.lerp(deltaZ, v00, v01);
        float value1 = Mth.lerp(deltaZ, v10, v11);
        return Mth.lerp(deltaX, value0, value1);
    }

    /** 客户端 tick：drain 请求 + 预取环扫描 + 周期自检。 */
    public static void tick(Level level) {
        bind(level);
        if (ClimateQuery.getModelIfActive(level) == null) return;

        requestMissing(level);

        if (level.getGameTime() % SELF_CHECK_INTERVAL_TICKS == 0) {
            selfCheck(level);
        }
    }

    /** 响应到达（netty 线程）。 */
    public static void onRegionClimate(int gridX, int gridZ, float temperature, int surfaceHeight, float rainfall) {
        if (REGION_POINTS.putIfAbsent(RegionClimatePayloads.gridKey(gridX, gridZ),
            new RegionPoint(temperature, surfaceHeight, rainfall)) == null) {
            long n = REGION_POINTS.size();
            if (Config.debugLogs() && n <= 5) {
                LOGGER.info("[estfc] region climate received #{} ({},{}) = {}°C, h={}, rain={}",
                    n, gridX, gridZ, temperature, surfaceHeight, rainfall);
            }
            if (n > REGION_CACHE_CAP) {
                REGION_POINTS.clear();
            }
        }
    }

    /** 缓存命中直接返回；未命中入队请求并返回 null。 */
    @Nullable
    private static RegionPoint point(int gridX, int gridZ) {
        RegionPoint cached = REGION_POINTS.get(RegionClimatePayloads.gridKey(gridX, gridZ));
        if (cached != null) return cached;
        if (PENDING.size() < PENDING_CAP) {
            PENDING.add(RegionClimatePayloads.gridKey(gridX, gridZ));
        }
        return null;
    }

    /** 先按预算 drain 请求集合；玩家跨网格单元或每 PREFETCH_RESCAN_TICKS 补扫一次环。 */
    private static void requestMissing(Level level) {
        // 先把预取环加入待请求集合，再按预算发送；否则 Voxy 的按需请求会把 PENDING
        // 占满，预取环永远拿不到预算，远处大量 no region sample。
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            int gridX = player.blockPosition().getX() >> 7;
            int gridZ = player.blockPosition().getZ() >> 7;
            long tick = level.getGameTime();
            if (gridX != lastScanGridX || gridZ != lastScanGridZ || tick - lastScanTick >= PREFETCH_RESCAN_TICKS) {
                lastScanGridX = gridX;
                lastScanGridZ = gridZ;
                lastScanTick = tick;

                int rings = Math.max(1, Config.dhTempPrefetchRadius() >> 7);
                for (int ring = 0; ring <= rings; ring++) {
                    for (int i = -ring; i <= ring; i++) {
                        point(gridX + i, gridZ - ring);
                        if (ring > 0) point(gridX + i, gridZ + ring);
                        point(gridX - ring, gridZ + i);
                        if (ring > 0) point(gridX + ring, gridZ + i);
                    }
                }
            }
        }

        int budget = Config.dhTempRequestsPerTick();
        for (Iterator<Long> it = PENDING.iterator(); it.hasNext() && budget > 0; ) {
            long key = it.next();
            it.remove();
            if (!REGION_POINTS.containsKey(key)) {
                send(key);
                budget--;
            }
        }
    }

    private static void send(long key) {
        int gridX = (int) (key >> 32);
        int gridZ = (int) key;
        RegionClimatePayloads.sendRequest(new RegionClimateRequest(gridX, gridZ));
    }

    /** 自检：玩家位置（已加载，客户端有真实 ChunkData）对比缓存插值。 */
    private static void selfCheck(Level level) {
        try {
            Player player = Minecraft.getInstance().player;
            if (player == null) return;
            BlockPos pos = player.blockPosition();
            if (!level.isLoaded(pos)) return;

            float real = net.dries007.tfc.world.chunkdata.ChunkData.get(level, pos)
                .getAverageTemp(pos.getX(), pos.getZ());
            float cached = avgSeaLevelTemp(pos.getX(), pos.getZ());
            if (Float.isNaN(cached)) return;
            if (Config.debugLogs() && Math.abs(cached - real) > 0.01f) {
                LOGGER.warn("[estfc] region temp mismatch at {}: cache={} chunkData={}", pos, cached, real);
            }
        } catch (Exception ignored) {
        }
    }

    /** 世界/维度切换时清空全部缓存。 */
    private static void bind(Level level) {
        if (boundLevel.get() == level) return;
        REGION_POINTS.clear();
        PENDING.clear();
        lastScanTick = Long.MIN_VALUE;
        boundLevel = new WeakReference<>(level);
    }
}
