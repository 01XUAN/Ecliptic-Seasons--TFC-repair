package me.xuan.estfc.mixin;

import net.dries007.tfc.util.tracker.WeatherHelpers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TFC WeatherHelpers 注入。
 *
 * <ul>
 *   <li>{@code onTickChunk} — HEAD cancel，阻止 TFC 放置 SnowPileBlock 物理雪方块</li>
 *   <li>{@code advanceWeatherCycle} — RETURN 注入，TFC 更新 vanilla rainLevel 后驱动 ES 雪深更新</li>
 * </ul>
 */
@Mixin(value = WeatherHelpers.class, remap = false)
public abstract class WeatherHelpersMixin {

    @Unique
    private static final Logger ESTFC_LOG = LoggerFactory.getLogger("estfc");
    @Unique
    private static boolean LOGGED_CANCEL = false;
    @Unique
    private static boolean LOGGED_DRIVE = false;

    // ==================== 阻止 TFC 物理雪块 ====================

    /**
     * 取消 TFC 的 chunk tick 积雪处理。
     * TFC 不再放置 SnowPileBlock/IcePileBlock/IcicleBlock，
     * 转而由 ES 的 SnowyStatusKeeper 提供纯视觉积雪。
     */
    @Inject(method = "onTickChunk", at = @At("HEAD"), cancellable = true)
    private static void estfc$cancelTfcSnowAccumulation(ServerLevel level, ChunkAccess chunk, CallbackInfo ci) {
        if (!LOGGED_CANCEL) {
            LOGGED_CANCEL = true;
            ESTFC_LOG.info("[estfc] TFC onTickChunk CANCELLED — using ES visual snow instead");
        }
        ci.cancel();
    }

    // ==================== 驱动 ES 天气 ====================

    /**
     * TFC 每 tick 在 {@code advanceWeatherCycle} 中更新 vanilla 的
     * rainLevel/thunderLevel。在 TAIL 注入我们自己的群系雪深更新。
     *
     * <p>仅在 TFC 成功处理天气周期（返回 true）时执行。</p>
     */
    @Inject(method = "advanceWeatherCycle", at = @At("RETURN"))
    private static void estfc$afterAdvanceWeatherCycle(ServerLevel level,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;

        if (!LOGGED_DRIVE) {
            LOGGED_DRIVE = true;
            ESTFC_LOG.info("[estfc] Driving ES snow cycle from TFC advanceWeatherCycle TAIL");
        }

        WeatherManagerMixin.estfc$tickWeatherCycle(level);
    }
}
