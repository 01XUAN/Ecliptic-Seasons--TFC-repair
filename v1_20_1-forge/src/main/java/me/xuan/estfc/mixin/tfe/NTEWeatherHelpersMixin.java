package me.xuan.estfc.mixin.tfe;

import me.xuan.estfc.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 关闭 TerraFirmaEarth（1.20.1 的 TFC 1.21 内容向下移植模组）自带的物理雪/冰放置。
 *
 * <p>TFE 也注入 {@code EnvironmentHelpers.tickChunk} 的 HEAD 并 cancel，在里面调用本方法。
 * 同一注入点上的多个 handler <b>都会执行</b>，{@code ci.cancel()} 只取消目标方法体、
 * 并不跳过其它 handler —— 所以给 estfc 的 mixin 提优先级没有用，必须直接拦 TFE 的入口。</p>
 *
 * <p>TFE 是可选模组：本 mixin 放在 {@code estfc.tfe.mixins.json}（{@code required: false}），
 * 编译期由 {@code libs/} 下的本地 jar 提供目标类校验，运行期由 TFE 自己提供。
 * 用 {@code targets} 字符串而非类字面量 —— 这样本 mixin 的常量池里不含 TFE 类引用，
 * TFE 未安装时连类加载都不会失败。</p>
 */
@Mixin(targets = "com.newterraearth.tfe.world.NTEWeatherHelpers", remap = false)
public abstract class NTEWeatherHelpersMixin {

    @Inject(method = "onTickChunk", at = @At("HEAD"), cancellable = true)
    private static void estfc$cancelTfeSnow(ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
        if (Config.tfcPhysicalSnow()) return;
        ci.cancel();
    }
}
