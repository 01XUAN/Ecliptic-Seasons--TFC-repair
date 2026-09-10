package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.map.BiomeHolder;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import me.xuan.estfc.weather.ChunkSnowScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * ES {@code forceChunkUpdateHeight} 之后，把该区块排进覆雪刷新队列。
 *
 * <p><b>这里只入队，不做任何 TFC 气候读取。</b>该注入点运行在 {@code ChunkMap} 的区块加载
 * future 回调中（主线程），此时调用 {@code Climate.getTemperature} → {@code ChunkData.get}
 * → {@code Level.getBiome} → {@code Level.getChunk} 会把加载任务提交给主线程并 park 等待，
 * 而主线程就是自己 —— 自死锁。真正的刷新在服务端 tick 执行，见 {@link ChunkSnowScheduler}。</p>
 */
@Mixin(value = SnowyMapChecker.class, remap = false)
public abstract class SnowyMapCheckerMixin {

    @Inject(method = "forceChunkUpdateHeight", at = @At("RETURN"))
    private static void estfc$enqueueSnowRefresh(
        ServerLevel level, ChunkAccess chunk, ChunkInfoMap chunkMap,
        @Nullable BiomeHolder biomeHolder, boolean loadingChunk, CallbackInfo ci) {

        if (chunkMap == null) return;
        ChunkSnowScheduler.enqueue(level, chunkMap, chunk.getPos(), loadingChunk);
    }
}
