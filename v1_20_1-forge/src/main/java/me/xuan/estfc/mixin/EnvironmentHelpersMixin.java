package me.xuan.estfc.mixin;

import net.dries007.tfc.util.EnvironmentHelpers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TFC 1.20.1 的 {@code EnvironmentHelpers.tickChunk} 负责放置物理雪堆/冰/冰柱。
 * HEAD 取消它，覆雪完全交给 ES 视觉系统（与 1.21.1 取消 {@code WeatherHelpers.onTickChunk} 等价）。
 *
 * <p>TerraFirmaEarth 也在同一方法的 HEAD 上注入并 cancel（它调用自己的
 * {@code NTEWeatherHelpers.onTickChunk} 放雪）。同注入点的多个 handler 都会执行，
 * 提优先级没用 —— 物理雪由 {@code me.xuan.estfc.mixin.tfe.NTEWeatherHelpersMixin} 单独拦。</p>
 */
@Mixin(value = EnvironmentHelpers.class, remap = false, priority = 1500)
public abstract class EnvironmentHelpersMixin {

    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private static void estfc$cancelTfcSnowAccumulation(ServerLevel level, LevelChunk chunk,
                                                       ProfilerFiller profiler, CallbackInfo ci) {
        ci.cancel();
    }
}
