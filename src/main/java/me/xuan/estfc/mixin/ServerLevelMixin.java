package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.api.util.EclipticUtil;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.teamtea.eclipticseasons.common.core.snow.WeatherStatusKeeper;
import me.xuan.estfc.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * tickChunk 尾部注入——每 N tick 执行一次积雪/融雪（可配置，默认 20 tick）。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "tickChunk", at = @At("TAIL"))
    private void estfc$tickChunkSnowProcessing(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;

        if (!EclipticUtil.canSnowyBlockInteract()) return;
        if (level.getGameTime() % Config.tickChunkIntervalTicks() != 0) return;

        SnowyStatusKeeper snowyKeeper = SnowyMapChecker.getSnowyStatusKeeper(chunk);
        WeatherStatusKeeper weatherKeeper = SnowyMapChecker.getWeatherStatusKeeper(chunk);
        ChunkInfoMap chunkInfoMap = MapChecker.getChunkInfoMapOrCreate(level, chunk.getPos());

        if (snowyKeeper == null || weatherKeeper == null || chunkInfoMap == null) return;

        BlockPos randomPos = level.getBlockRandomPos(
            chunk.getPos().getMinBlockX(), 0,
            chunk.getPos().getMinBlockZ(), 15
        );

        snowyKeeper.tickChunk(level, chunk, chunk.getPos(), randomPos, chunkInfoMap, weatherKeeper);
    }
}
