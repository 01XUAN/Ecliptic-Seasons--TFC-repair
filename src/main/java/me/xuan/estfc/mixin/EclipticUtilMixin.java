package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.api.util.EclipticUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 强制启用 ES 的积雪视觉系统，但不再禁用 ES 的天气循环。
 *
 * ES 的 agentAdvanceWeatherCycle → updateSnowOrMelt 负责维护
 * BiomeWeather.snowDepth（全局 biome 级雪深），该值通过
 * BiomeWeatherMessage 每 5 秒同步到客户端，是 ES 全局雪景的核心。
 *
 * 我们保留此循环，但通过 WeatherManagerMixin 覆盖 getSnowStatus
 * 使其使用 TFC 气候数据而非 ES 的季节判断。
 */
@Mixin(value = EclipticUtil.class, remap = false)
public abstract class EclipticUtilMixin {

    @Unique
    private static final Logger ESTFC_LOG = LoggerFactory.getLogger("estfc");

    @Unique
    private static boolean LOGGED = false;

    /**
     * 强制启用 ES 的积雪视觉系统 (SnowyStatusKeeper)。
     */
    @Inject(method = "canSnowyBlockInteract", at = @At("HEAD"), cancellable = true)
    private static void estfc$forceSnowyBlockInteract(CallbackInfoReturnable<Boolean> cir) {
        if (!LOGGED) {
            LOGGED = true;
            ESTFC_LOG.info("[estfc] Snow visual system ENABLED. ES weather cycle preserved, driven by TFC climate.");
        }
        cir.setReturnValue(true);
    }
}
