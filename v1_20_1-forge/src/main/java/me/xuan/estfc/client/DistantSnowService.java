package me.xuan.estfc.client;

import me.xuan.estfc.Config;
import me.xuan.estfc.weather.SnowDepthRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * voxy 远景覆雪计算（客户端，未加载区块）。
 * voxy 经 VoxyTool.changeBlockId 汇入 {@code MapChecker.shouldSnowAtBiome}，
 * estfc 在 {@code MapCheckerMixin} 接线点上把雪判据换成 TFC 温度。
 */
public final class DistantSnowService {

    private static final Logger LOGGER = LoggerFactory.getLogger("estfc");
    private static final AtomicInteger LOW_DEPTH_LOGS = new AtomicInteger();
    private static final AtomicInteger HIGH_DEPTH_LOGS = new AtomicInteger();

    private DistantSnowService() {}

    /** 远景有效雪深（0-100）：voxy 体素 y=真实方块高度；区域数据未到 → 不显示雪。 */
    public static int calcSnowDepthClient(Level level, BlockPos pos, int biomeDepth) {
        RegionClimateCache.Sample sample = RegionClimateCache.sampleClimate(pos.getX(), pos.getZ());
        // 区域数据未到：不显示雪；已算好的最近点会由 sampleClimate 返回
        if (sample == null) {
            if (Config.debugLogs() && biomeDepth < 10 && LOW_DEPTH_LOGS.incrementAndGet() <= 8) {
                LOGGER.warn("[estfc] voxy no region sample: pos={} biomeDepth={}", pos, biomeDepth);
            }
            return 0;
        }
        // Voxy 体素 y 是它自己的真实方块高度，保持使用 pos.y；区域高度不作为温度海拔。
        Float temp = DistantTemperature.distantTemperature(level, sample, pos.getY());
        if (temp == null) return 0;
        // 服务端还没播种该群系（C=0）时，用和服务端首次播种相同的气候种子，
        // 不等群系天气同步；暖/干旱群系 seed 仍是 0。
        int coverage = biomeDepth;
        if (coverage <= 0) {
            coverage = SnowDepthRules.climateSeed(temp, sample.rainfall());
        }
        int depth = SnowDepthRules.depthFromCoverage(temp, coverage, sample.rainfall());
        if (Config.debugLogs() && depth >= 90 && temp > -2f && temp < 2f
            && HIGH_DEPTH_LOGS.incrementAndGet() <= 8) {
            LOGGER.warn("[estfc] voxy near-zero full snow: pos={} y={} coverage={} temp={} sampleHeight={} depth={} rain={}",
                pos, pos.getY(), coverage, temp, (int) sample.height(), depth, sample.rainfall());
        }
        if (Config.debugLogs() && depth < 10 && (coverage >= 50 || coverage < 10) && LOW_DEPTH_LOGS.incrementAndGet() <= 8) {
            LOGGER.warn("[estfc] voxy low depth: pos={} y={} coverage={} temp={} sampleHeight={} depth={} rain={}",
                pos, pos.getY(), coverage, temp, (int) sample.height(), depth, sample.rainfall());
        }
        return depth;
    }
}
