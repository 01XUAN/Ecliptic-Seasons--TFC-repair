package me.xuan.estfc.mixin;

import net.dries007.tfc.world.feature.IceAndSnowFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 关闭 TFC 的 worldgen 覆雪特征 {@code tfc:ice_and_snow}。
 *
 * <p>该特征只做一件事：调用 {@code Climate.onChunkLoad} →
 * {@code OverworldClimateModel.onChunkLoad}，在那里按噪声+温度往地表放
 * {@code minecraft:snow} 层 / {@code tfc:snow_pile}，并给草方块设 SNOWY
 * （见 TFC 1.20.1 {@code OverworldClimateModel} 约 253-290 行）。</p>
 *
 * <p>因为发生在世界生成阶段，表现为「只有新加载（新生成）的区块有雪层，
 * 站着下雪反而不会长」——tick 路径早已被 estfc 关闭。
 * TFC 自己的注释也写了这段代码「不该被使用」。</p>
 */
@Mixin(value = IceAndSnowFeature.class, remap = false)
public abstract class IceAndSnowFeatureMixin {

    @org.spongepowered.asm.mixin.Unique
    private static int estfc$calls = 0;

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void estfc$skipWorldgenSnow(FeaturePlaceContext<NoneFeatureConfiguration> context,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (estfc$calls++ < 5) {
            org.slf4j.LoggerFactory.getLogger("estfc")
                .info("[estfc] IceAndSnowFeature.place called at {} (cancelled)", context.origin());
        }
        cir.setReturnValue(true);
    }
}
