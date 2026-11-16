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
        // depthFromCoverage 内部已用气候种子给冷区兜底，C 只在此基础上累积。
        int depth = SnowDepthRules.depthFromCoverage(temp, biomeDepth, sample.rainfall());
        if (Config.debugLogs() && depth >= 90 && temp > -2f && temp < 2f
            && HIGH_DEPTH_LOGS.incrementAndGet() <= 8) {
            LOGGER.warn("[estfc] voxy near-zero full snow: pos={} y={} biomeDepth={} temp={} sampleHeight={} depth={} rain={}",
                pos, pos.getY(), biomeDepth, temp, (int) sample.height(), depth, sample.rainfall());
        }
        if (Config.debugLogs() && depth < 10 && (biomeDepth >= 50 || biomeDepth < 10) && LOW_DEPTH_LOGS.incrementAndGet() <= 8) {
            LOGGER.warn("[estfc] voxy low depth: pos={} y={} biomeDepth={} temp={} sampleHeight={} depth={} rain={}",
                pos, pos.getY(), biomeDepth, temp, (int) sample.height(), depth, sample.rainfall());
        }
        return depth;
    }
}
