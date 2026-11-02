package me.xuan.estfc.mixin;

import me.xuan.estfc.Config;
import net.dries007.tfc.world.feature.IceAndSnowFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TFC 世界生成物理雪：配置关闭时直接跳过 IceAndSnowFeature。
 */
// 1.21.1 NeoForge 运行时是 official 名，TFC 的 place 方法名不会被重映射
@Mixin(value = IceAndSnowFeature.class, remap = false)
public abstract class IceAndSnowFeatureMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("estfc");
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void estfc$cancelWorldgenSnow(FeaturePlaceContext<NoneFeatureConfiguration> context,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (Config.tfcPhysicalSnow()) return;
        if (Config.debugLogs() && LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[estfc] IceAndSnowFeature canceled");
        }
        cir.setReturnValue(false);
    }
}
