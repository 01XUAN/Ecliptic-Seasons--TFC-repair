package me.xuan.estfc.mixin;

import me.xuan.estfc.Config;
import me.xuan.estfc.weather.WeatherCycleService;
import net.dries007.tfc.util.tracker.WeatherHelpers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TFC WeatherHelpers 注入。
 * onTickChunk — HEAD cancel，阻止 TFC 放置物理雪块/冰柱/冰堆
 * advanceWeatherCycle — RETURN 注入，驱动 ES biome 雪深同步
 */
@Mixin(value = WeatherHelpers.class, remap = false)
public abstract class WeatherHelpersMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("estfc");
    private static final AtomicBoolean CATCHUP_LOGGED = new AtomicBoolean();

    /**
     * 默认取消 TFC chunk tick 积雪处理，TFC 不再放置 SnowPileBlock/IcePileBlock/IcicleBlock；
     * 配置 {@code tfcPhysicalSnow=true} 时放行。
     */
    @Inject(method = "onTickChunk", at = @At("HEAD"), cancellable = true)
    private static void estfc$cancelTfcSnowAccumulation(ServerLevel level, ChunkAccess chunk, CallbackInfo ci) {
        if (Config.tfcPhysicalSnow()) return;
        ci.cancel();
    }

    /** 未随机 tick 的区块按历史天气补雪：没下雪也会补出雪层/雪堆。 */
    @Inject(method = "tickCatchUp", at = @At("HEAD"), cancellable = true)
    private static void estfc$cancelTfcSnowCatchUp(ServerLevel level, CallbackInfo ci) {
        if (Config.tfcPhysicalSnow()) return;
        if (Config.debugLogs() && CATCHUP_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[estfc] TFC snow catch-up canceled");
        }
        ci.cancel();
    }

    /**
     * TFC 更新 vanilla rainLevel/thunderLevel 后，驱动 ES 群系雪深更新。
     */
    @Inject(method = "advanceWeatherCycle", at = @At("RETURN"))
    private static void estfc$afterAdvanceWeatherCycle(ServerLevel level,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;
        WeatherCycleService.tickWeatherCycle(level);
    }
}
