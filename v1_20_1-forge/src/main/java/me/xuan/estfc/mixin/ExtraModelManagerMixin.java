package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.config.CommonConfig;
import me.xuan.estfc.weather.ClimateQuery;
import me.xuan.estfc.weather.SnowDepthRules;
import net.dries007.tfc.util.climate.Climate;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import com.teamtea.eclipticseasons.api.misc.client.IMapSlice;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * maySnowyAt 的回退：无客户端 map 数据时，用服务端累积雪深按同一套
 * {@code depthFromCoverage = 累积覆盖率} 判断。
 */
@Mixin(value = ExtraModelManager.class, remap = false)
public abstract class ExtraModelManagerMixin {

    @Inject(method = "maySnowyAt", at = @At("RETURN"), cancellable = true)
    private static void estfc$maySnowyAtTfcFallback(
        Level level, IMapSlice mapSlice, BlockState state, BlockPos checkPos,
        RandomSource random, long seed, CallbackInfoReturnable<Boolean> cir) {

        if (cir.getReturnValue()) {
            if (estfc$tooBright(level, checkPos)) cir.setReturnValue(false);
            return;
        }
        // 有服务端数据时信任服务端；但客户端雪图可能因为区块附件同步时序为空，
        // 只有玩家手动 NONE_SNOWY 才阻止回退。
        if (mapSlice != null
            && mapSlice.getSnowyStatus(checkPos) == com.teamtea.eclipticseasons.common.core.map.SnowyRemover.NONE_SNOWY) {
            return;
        }

        try {
            if (ClimateQuery.getModelIfActive(level) == null) return;

            // C=0 不等于没雪：depthFromCoverage 会用气候种子兜底冷湿位置
            int biomeDepth = ClimateQuery.getBiomeDepthAt(level, checkPos);

            float temp = ClimateQuery.instantTemperature(level, checkPos);
            float rainfall = ClimateQuery.averageRainfall(level, checkPos);
            int depth = SnowDepthRules.depthFromCoverage(temp, biomeDepth, rainfall);
            if (depth <= 0) return;

            if (!estfc$tooBright(level, checkPos)
                && SnowDepthRules.shouldSnow(depth, state.getSeed(checkPos))) {
                cir.setReturnValue(true);
            }
        } catch (Exception ignored) {}
    }

    @Unique
    private static boolean estfc$tooBright(Level level, BlockPos pos) {
        if (!CommonConfig.Snow.notSnowyNearGlowingBlock.get()) return false;
        return level.getBrightness(LightLayer.BLOCK, pos.above())
            >= CommonConfig.Snow.notSnowyNearGlowingBlockLevel.get();
    }
}
