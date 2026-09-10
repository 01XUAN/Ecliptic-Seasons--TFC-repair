package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusHandler;
import me.xuan.estfc.client.DebugCounters;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 临时诊断：客户端收到并应用覆雪状态包的次数。 */
@Mixin(value = SnowyStatusHandler.class, remap = false)
public abstract class SnowyStatusHandlerDebugMixin {

    @Inject(method = "setChunkWithUpdate", at = @At("HEAD"))
    private static void estfc$countApply(SnowyStatusHandler msg, LevelChunk chunkAt, CallbackInfo ci) {
        if (msg.initialSync) {
            DebugCounters.INITIAL_PACKETS.incrementAndGet();
        } else {
            DebugCounters.DELTA_PACKETS.incrementAndGet();
            DebugCounters.DELTA_POSITIONS.addAndGet(msg.attachment.getPosListUpdate().size());
        }
    }
}
