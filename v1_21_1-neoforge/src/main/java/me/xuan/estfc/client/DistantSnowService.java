package me.xuan.estfc.client;

import me.xuan.estfc.weather.SnowDepthRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * voxy 远景覆雪计算（客户端，未加载区块）。
 * voxy 经 VoxyTool.changeBlockId 汇入 {@code MapChecker.shouldSnowAtBiome}，
 * estfc 在 {@code MapCheckerMixin} 接线点上把雪判据换成 TFC 温度。
 */
public final class DistantSnowService {

    private DistantSnowService() {}

    /** 远景有效雪深（0-100）：voxy 体素 y=真实地表 → 含海拔的精确温度 + 降水 gate；数据未同步时回退纯 biomeDepth。 */
    public static int calcSnowDepthClient(Level level, BlockPos pos, int biomeDepth) {
        RegionClimateCache.Sample sample = RegionClimateCache.sampleClimate(pos.getX(), pos.getZ());
        if (sample == null) return biomeDepth;
        Float temp = DistantTemperature.distantTemperature(level, sample, pos.getY());
        if (temp == null) return biomeDepth;
        return SnowDepthRules.depthFromTemp(temp, biomeDepth, sample.rainfall());
    }
}
