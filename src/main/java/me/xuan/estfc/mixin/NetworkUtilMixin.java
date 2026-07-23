package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.network.message.BiomeWeatherMessage;
import me.xuan.estfc.SnowDepthCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 拦截 BiomeWeatherMessage，将 biome 雪深缓存到 SnowDepthCache。
 */
@Mixin(value = com.teamtea.eclipticseasons.common.network.NetworkUtil.class, remap = false)
public abstract class NetworkUtilMixin {

    @Inject(method = "processBiomeWeatherMessage", at = @At("HEAD"))
    private static void estfc$cacheBiomeWeather(BiomeWeatherMessage msg, IPayloadContext context, CallbackInfo ci) {
        byte[] snowDepth = msg.snowDepth;
        int len = Math.min(snowDepth.length, SnowDepthCache.DEPTHS.length);
        System.arraycopy(snowDepth, 0, SnowDepthCache.DEPTHS, 0, len);
    }
}
