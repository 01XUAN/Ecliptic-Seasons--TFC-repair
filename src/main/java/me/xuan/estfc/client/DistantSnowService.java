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

    /** DH/Voxy 远景雪深 (0-100)。 */
    public static int calcSnowDepthClient(Level level, ClimateModel model, BlockPos pos,
                                           int biomeDepth) {
        float temp = TfcUnloadedTempEstimator.getCorrectedTemperature(level, model, pos);
        if (temp > Config.snowMeltTemp()) return 0;
        return SnowDepthRules.calcEffectiveDepth(temp, ClimateQuery.isPrecipitating(level), biomeDepth);
    }
}
