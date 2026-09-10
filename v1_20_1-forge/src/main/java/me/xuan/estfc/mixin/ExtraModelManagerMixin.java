package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import me.xuan.estfc.weather.ClimateQuery;
import me.xuan.estfc.weather.SnowDepthRules;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import com.teamtea.eclipticseasons.api.misc.client.IMapSlice;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * maySnowyAt 的 biome 累积雪深回退——只在降水时触发。
 *
 * <p>ES 原生路径：isSnowyBlock → 有标记返回，无标记回退 biome 雪深。
 * estfc 改为查服务端逐步累积的 biome 雪深（由 tickWeatherCycle 驱动）。</p>
 */
@Mixin(value = ExtraModelManager.class, remap = false)
public abstract class ExtraModelManagerMixin {

    @Inject(method = "maySnowyAt", at = @At("RETURN"), cancellable = true)
    private static void estfc$maySnowyAtTfcFallback(
        Level level, IMapSlice mapSlice, BlockState state, BlockPos checkPos,
        RandomSource random, long seed, CallbackInfoReturnable<Boolean> cir) {

        if (cir.getReturnValue()) return;
        // 有服务端数据时信任服务端，只在无数据时回退
        if (mapSlice != null) return;

        try {
            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) return;

            // 只在降水时回退
            if (model == null || !ClimateQuery.isPrecipitating(level, checkPos)) return;

            int biomeDepth = ClimateQuery.getBiomeDepthAt(level, checkPos);
            if (biomeDepth <= 0) return;

            if (SnowDepthRules.shouldSnow(biomeDepth, state.getSeed(checkPos))) {
                cir.setReturnValue(true);
            }
        } catch (Exception ignored) {}
    }
}
