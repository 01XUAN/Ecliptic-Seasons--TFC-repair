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

/**
 * 阻止 TFC 放置 SnowPileBlock 等物理雪方块。
 *
 * TFC 的 {@link WeatherHelpers#onTickChunk} 负责在 chunk tick 时
 * 放置/融化 SnowPileBlock、IcePileBlock、IcicleBlock。
 * 我们将其完全取消，改为由 ES 的 SnowyStatusKeeper 提供纯视觉积雪。
 *
 * 注意：这也会阻止 TFC 的融雪逻辑，但世界生成阶段已有的
 * TFC 雪块不受影响（它们不会在 tick 中被新增）。
 */
@Mixin(value = WeatherHelpers.class, remap = false)
public abstract class WeatherHelpersMixin {

    @Unique
    private static final Logger ESTFC_LOG = LoggerFactory.getLogger("estfc");

    @Unique
    private static boolean LOGGED = false;

    @Inject(method = "onTickChunk", at = @At("HEAD"), cancellable = true)
    private static void estfc$cancelTfcSnowAccumulation(ServerLevel level, ChunkAccess chunk, CallbackInfo ci) {
        if (!LOGGED) {
            LOGGED = true;
            ESTFC_LOG.info("[estfc] TFC onTickChunk CANCELLED — SnowPileBlock placement disabled.");
        }
        ci.cancel();
    }
}
