package me.xuan.estfc.mixin;

import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 强制 ES 的 DistantHorizonsWinterLOD 分支不生效。
 *
 * <p>ES 在 DH 的 {@code setRenderColumnView} 里包装了部分 {@code getBlockColor} 调用；
 * 只要它的配置为 true，就会先返回 ES 的雪/冰色，estfc 的 {@code DhApiBlockColorOverrideEvent}
 * 收不到这些调用，两套覆雪混用会出条纹和 128 格网格。这里直接让 ES 的两个入口返回
 * 「不处理」，等价于把 {@code DistantHorizonsWinterLOD} 设为 false，但无需改对方配置文件。</p>
 */
@Mixin(targets = "com.teamtea.eclipticseasons.compat.distanthorizons.DHTool", remap = false)
public abstract class DHToolMixin {

    @Inject(method = "computeBaseColor", at = @At("HEAD"), cancellable = true)
    private static void estfc$disableEsDhSnowColor(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(null);
    }

    @Inject(method = "shouldFrozen", at = @At("HEAD"), cancellable = true)
    private static void estfc$disableEsDhIce(CallbackInfoReturnable<IBlockStateWrapper> cir) {
        cir.setReturnValue(null);
    }
}
