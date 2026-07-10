package me.xuan.estfc.mixin;

import net.dries007.tfc.util.tracker.WeatherHelpers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WeatherHelpers.class, remap = false)
public abstract class WeatherHelpersMixin {

    @Inject(method = "onTickChunk", at = @At("HEAD"), cancellable = true)
    private static void estfc$cancelTfcSnowAccumulation(ServerLevel level, ChunkAccess chunk, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "advanceWeatherCycle", at = @At("RETURN"))
    private static void estfc$afterAdvanceWeatherCycle(ServerLevel level,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;

        me.xuan.estfc.WeatherBridge.tickWeatherCycle(level);
    }
}
