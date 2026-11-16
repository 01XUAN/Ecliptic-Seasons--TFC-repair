package me.xuan.estfc.mixin;

import me.xuan.estfc.client.ChunkDataSyncRetry;
import net.dries007.tfc.network.ChunkWatchPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TFC 1.21.1 的 {@code ChunkWatchPacket.handle()} 在区块尚未加载时直接丢弃气候数据
 * （{@code ChunkData.status() == INVALID}）。NeoForge 的 {@code ChunkWatchEvent.Sent}
 * 在区块包真正发出前触发，所以客户端可能先收到这个包。
 * 交给 {@link ChunkDataSyncRetry} 入队，区块加载后再写入。
 */
@Mixin(value = ChunkWatchPacket.class, remap = false)
public abstract class ChunkWatchPacketMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void estfc$deferUntilChunkLoaded(CallbackInfo ci) {
        try {
            ChunkDataSyncRetry.receive((ChunkWatchPacket) (Object) this);
        } catch (Exception ignored) {
            // 客户端网络处理路径：异常不能逃逸
        }
        ci.cancel();
    }
}
