package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.api.util.EclipticUtil;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.teamtea.eclipticseasons.common.core.snow.WeatherStatusKeeper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 替代 ES 不可靠的 @ModifyExpressionValue 注入点，
 * 在 tickChunk 尾部直接驱动 SnowyStatusKeeper.tickChunk()。
 *
 * <p>实际的雪判断（SNOW/SNOW_MELT/snowDepth）由
 * WeatherManagerMixin 在 WeatherManager 方法级覆盖，
 * 使用 TFC Climate API，对 biome 全局层和 per-chunk 层统一生效。</p>
 *
 * <h3>与 ES 的协同</h3>
 * <p>ES 的 {@code MixinServerLevel.tickChunk_our_snow}（@ModifyExpressionValue）处理一个随机位置，
 * 然后 {@code tickChunk_sync}（@At RETURN）调用 {@code updateAndSend} 同步。</p>
 *
 * <p>本 Mixin 在 TAIL（RETURN 之后）额外处理一个随机位置，并<b>自行调用 updateAndSend</b>。
 * 因为 TAIL 在 RETURN 之后执行，ES 的 RETURN sync 已经错过了我们的修改。</p>
 *
 * <h3>节奏控制</h3>
 * <p>每个 chunk 每 20 tick (~1秒) 才额外处理一个随机位置，分散 CPU 负载。
 * 无玩家时跳过 — 没有人能看到雪，不需要更新。</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Unique
    private static final Logger ESTFC_LOG = LoggerFactory.getLogger("estfc");
    @Unique
    private static boolean LOGGED = false;

    @Inject(method = "tickChunk", at = @At("TAIL"))
    private void estfc$tickChunkSnowProcessing(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;

        if (!LOGGED) {
            LOGGED = true;
            ESTFC_LOG.info("[estfc] ServerLevelMixin active — extra tickChunk processing (1/20 rate, self-sync)");
        }

        if (!EclipticUtil.canSnowyBlockInteract()) return;

        // 无玩家时不需要额外处理 — 没有人能看到雪，避免关闭时浪费 CPU
        if (level.players().isEmpty()) return;

        // 每个 chunk 每 20 tick (~1秒) 才额外处理一个随机位置，避免过载
        long chunkKey = chunk.getPos().toLong();
        if (Math.floorMod(chunkKey, 20) != Math.floorMod(level.getGameTime(), 20)) return;

        try {
            SnowyStatusKeeper snowyKeeper = SnowyMapChecker.getSnowyStatusKeeper(chunk);
            WeatherStatusKeeper weatherKeeper = SnowyMapChecker.getWeatherStatusKeeper(chunk);
            ChunkInfoMap chunkInfoMap = MapChecker.getChunkInfoMapOrCreate(level, chunk.getPos());

            if (snowyKeeper == null || weatherKeeper == null || chunkInfoMap == null) return;

            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();
            int minY = chunk.getMinBuildHeight();
            int yRange = chunk.getMaxBuildHeight() - 1 - minY;
            BlockPos randomPos;
            if (yRange <= 15) {
                randomPos = level.getBlockRandomPos(minX, minY, minZ, 15);
            } else {
                randomPos = new BlockPos(
                    minX + level.random.nextInt(16),
                    minY + level.random.nextInt(yRange + 1),
                    minZ + level.random.nextInt(16)
                );
            }

            // tickChunk 在 TAIL 中执行（ES 的 RETURN sync 已经错过），必须自行 sync
            snowyKeeper.tickChunk(level, chunk, chunk.getPos(), randomPos, chunkInfoMap, weatherKeeper);
            snowyKeeper.updateAndSend(level, chunk);
            weatherKeeper.updateAndSend(level, chunk);
        } catch (Exception e) {
            ESTFC_LOG.error("[estfc] Snow processing failed: {}", e.toString());
        }
    }
}
