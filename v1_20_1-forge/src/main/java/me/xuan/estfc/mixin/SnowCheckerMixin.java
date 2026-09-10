package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.snow.SnowChecker;
import me.xuan.estfc.weather.SnowDefinitionRegistrar;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 ES 装载 datapack 雪定义（{@code SnowChecker.resetUpdate}，服务端/客户端都触发）之后，
 * 由 {@link SnowDefinitionRegistrar} 补齐 datapack 未覆盖的 TFC 方块雪定义，
 * 使服务端分类（决定是否标雪）与客户端渲染一致。
 */
@Mixin(value = SnowChecker.class, remap = false)
public abstract class SnowCheckerMixin {

    @Inject(method = "resetUpdate", at = @At("RETURN"))
    private static void estfc$registerTfcSnowDefs(RegistryAccess registryAccess, boolean isServer, CallbackInfo ci) {
        SnowDefinitionRegistrar.registerDefaults();
    }
}
