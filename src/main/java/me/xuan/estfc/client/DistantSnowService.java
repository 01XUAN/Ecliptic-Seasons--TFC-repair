package me.xuan.estfc.client;

import me.xuan.estfc.Config;
import me.xuan.estfc.weather.ClimateQuery;
import me.xuan.estfc.weather.SnowDepthRules;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * DH/Voxy 远景覆雪计算（客户端，未加载区块）。
 *
 * <p>硬性约束：不得引用任何 DH 类型（com.seibel.*），
 * 那些只允许出现在 DhLevelWrapperMixin 里。</p>
 */
public final class DistantSnowService {

    private DistantSnowService() {}

    /** 不透明纯白 0xFFFFFFFF */
    public static final int OPAQUE_WHITE_ARGB = 0xFFFFFFFF;

    /** DH/Voxy 远景雪深 (0-100)。 */
    public static int calcSnowDepthClient(Level level, ClimateModel model, BlockPos pos,
                                           int biomeDepth) {
        float temp = TfcUnloadedTempEstimator.getCorrectedTemperature(level, model, pos);
        if (temp > Config.snowMeltTemp()) return 0;
        return SnowDepthRules.calcEffectiveDepth(temp, biomeDepth);
    }

    /** @return 该位置的覆雪着色 ARGB；无雪时原样返回 baseArgb。永不抛异常。 */
    public static int snowColorFor(Level level, ClimateModel model, BlockPos pos, int baseArgb) {
        try {
            int biomeDepth = ClimateQuery.getBiomeDepthAt(level, pos);
            int depth = calcSnowDepthClient(level, model, pos, biomeDepth);
            if (depth <= 0) return baseArgb;

            // depth 0→原始色, depth 100→纯白, 线性混合
            float factor = Math.min(depth / 100f, 1f);
            return blendToWhite(baseArgb, factor);
        } catch (Exception ignored) {
            return baseArgb;
        }
    }

    /** 将 ARGB 颜色按 factor [0,1] 向白色混合 */
    static int blendToWhite(int argb, float factor) {
        if (factor <= 0f) return argb;
        if (factor >= 1f) return OPAQUE_WHITE_ARGB;

        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        r = r + (int)((255 - r) * factor);
        g = g + (int)((255 - g) * factor);
        b = b + (int)((255 - b) * factor);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
