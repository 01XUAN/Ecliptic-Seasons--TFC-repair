package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.api.util.EclipticUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 强制启用 ES 的积雪视觉系统，但不再禁用 ES 的天气循环。
 */
@Mixin(value = EclipticUtil.class, remap = false)
public abstract class EclipticUtilMixin {

    @Inject(method = "canSnowyBlockInteract", at = @At("HEAD"), cancellable = true)
    private static void estfc$forceSnowyBlockInteract(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
