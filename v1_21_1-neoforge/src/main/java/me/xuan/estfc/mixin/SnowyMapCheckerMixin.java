package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.map.BiomeHolder;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import me.xuan.estfc.weather.ChunkSnowRefresh;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * 在 ES {@code forceChunkUpdateHeight} 完成后，用 TFC per-position 温度
 * 覆写雪的开关决策（比 ES 的 biome 级缓存更精确——同 biome 不同海拔温度不同）。
 * 具体逻辑见 {@link ChunkSnowRefresh}。
 */
@Mixin(value = SnowyMapChecker.class, remap = false)
public abstract class SnowyMapCheckerMixin {

    @Inject(method = "forceChunkUpdateHeight", at = @At("RETURN"))
    private static void estfc$afterForceChunkUpdate(
        ServerLevel level, ChunkAccess chunk, ChunkInfoMap chunkMap,
        @Nullable BiomeHolder biomeHolder, boolean loadingChunk, CallbackInfo ci) {

        if (chunkMap == null) return;
        ChunkSnowRefresh.refreshChunk(level, chunk, chunkMap, loadingChunk);
    }
}
