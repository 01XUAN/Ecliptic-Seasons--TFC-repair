package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.map.BiomeHolder;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.teamtea.eclipticseasons.common.core.snow.WeatherStatusKeeper;
import me.xuan.estfc.WeatherBridge;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.dries007.tfc.world.chunkdata.ChunkData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * 在 forceChunkUpdateHeight 中用 TFC 温度直接维护积雪状态。
 *
 * <h3>设计原则</h3>
 * TFC 温度是 (位置, 时间) 的确定性函数 → 同一位置同条件永远得到相同雪深。
 * 不依赖 biome 级累积雪深——新区块加载时立即根据当前温度显示正确覆雪率。
 * <p>
 * 温度→雪深映射（与 {@link WeatherManagerMixin} 一致）：
 * <ul>
 *   <li>temp < -10°C → 100</li>
 *   <li>temp ∈ [-10, 0] → 20 + |temp|*8</li>
 *   <li>temp > 0°C → 0</li>
 * </ul>
 * <p>
 * 覆雪判断：snowDepth > 0（不抖动），保证零下即覆雪。
 * 远景（MapCheckerMixin）也使用相同阈值。
 */
@Mixin(value = SnowyMapChecker.class, remap = false)
public abstract class SnowyMapCheckerMixin {

    @Unique
    private static final Logger LOG = LoggerFactory.getLogger("estfc");

    @Unique
    private static boolean DEBUG_DONE = false;
    @Unique
    private static int DEBUG_CHUNK_COUNT = 0;

    @Inject(method = "forceChunkUpdateHeight", at = @At("RETURN"))
    private static void estfc$afterForceChunkUpdate(
        ServerLevel level, ChunkAccess chunk, ChunkInfoMap chunkMap,
        @Nullable BiomeHolder biomeHolder, boolean loadingChunk, CallbackInfo ci) {

        if (chunkMap == null) return;

        boolean logDetail = DEBUG_CHUNK_COUNT < 20;

        if (logDetail) {
            LOG.info("[estfc:diag] forceChunkUpdateHeight RETURN — chunk({},{}) loadingChunk={} biomeHolder={}",
                chunk.getPos().x, chunk.getPos().z, loadingChunk,
                biomeHolder != null ? "present(v=" + biomeHolder.version() + ")" : "null");
        }

        try {
            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) {
                if (logDetail) LOG.info("[estfc:diag] TFC not available, skipping");
                return;
            }

            ChunkData chunkData = ChunkData.get(level, new BlockPos(
                chunk.getPos().getMinBlockX(), 64, chunk.getPos().getMinBlockZ()));
            if (chunkData == ChunkData.EMPTY) {
                if (logDetail) LOG.info("[estfc:diag] ChunkData EMPTY, skipping");
                return;
            }

            SnowyStatusKeeper keeper = SnowyMapChecker.getSnowyStatusKeeper(chunk);
            WeatherStatusKeeper weatherKeeper = SnowyMapChecker.getWeatherStatusKeeper(chunk);

            BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();
            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();
            int maxX = chunk.getPos().getMaxBlockX();
            int maxZ = chunk.getPos().getMaxBlockZ();

            int snowSet = 0, snowRemoved = 0, blocksProcessed = 0;
            int edgeSnowSet = 0, edgeBlocksProcessed = 0;
            int surfaceGroundBlocks = 0, edgeGroundBlocks = 0;
            float minTemp = Float.MAX_VALUE, maxTemp = Float.MIN_VALUE;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int surfaceHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    int solidHeight = chunkMap.getHeight(x, z);

                    int minY = Math.min(solidHeight, surfaceHeight);
                    int maxY = Math.max(solidHeight, surfaceHeight);

                    boolean edgeColumn = x <= minX + 2 || x >= maxX - 2
                        || z <= minZ + 2 || z >= maxZ - 2;

                    for (int y = minY; y <= maxY; y++) {
                        checkPos.set(x, y, z);
                        BlockState state = chunk.getBlockState(checkPos);
                        int flag = MapChecker.getDefaultBlockTypeFlag(state);

                        if (flag == MapChecker.FLAG_NONE && !state.blocksMotion()) continue;

                        int snowDepth = estfc$sampleSnowDepth(model, level, checkPos);
                        float temp = model.getInstantTemperature(level, checkPos);
                        if (temp < minTemp) minTemp = temp;
                        if (temp > maxTemp) maxTemp = temp;
                        boolean shouldSnow = snowDepth > 0;
                        boolean hasSnow = keeper.isSnowyBlock(checkPos);

                        blocksProcessed++;
                        if (edgeColumn) edgeBlocksProcessed++;

                        // Y == solidHeight 意味着这是"地表"方块（地面本身）
                        boolean isSurfaceBlock = (y == solidHeight);
                        if (isSurfaceBlock) {
                            surfaceGroundBlocks++;
                            if (edgeColumn) edgeGroundBlocks++;
                        }

                        if (shouldSnow && !hasSnow) {
                            keeper.set(checkPos, SnowyStatusKeeper.FLAG_SNOW);
                            snowSet++;
                            if (edgeColumn) edgeSnowSet++;
                        } else if (!shouldSnow && hasSnow) {
                            keeper.set(checkPos, SnowyStatusKeeper.FLAG_NONE);
                            snowRemoved++;
                        }
                    }
                }
            }

            // 始终输出每个 chunk 的摘要（前 20 个 chunk）
            if (DEBUG_CHUNK_COUNT <= 20) {
                float logMin = minTemp == Float.MAX_VALUE ? -99f : minTemp;
                float logMax = maxTemp == Float.MIN_VALUE ? 99f : maxTemp;
                // 取 chunk 中心点做参考位置
                int cx = chunk.getPos().getMinBlockX() + 8;
                int cz = chunk.getPos().getMinBlockZ() + 8;
                int cy = chunkMap.getHeight(cx, cz);
                LOG.info("[estfc:diag] chunk({},{}) refPos=({},{},{}) temp=[{},{}] processed={} set={} removed={} "
                    + "surfaceGnd={} edgeGnd={} edgeSet={} loadingChunk={}",
                    chunk.getPos().x, chunk.getPos().z,
                    cx, cy, cz,
                    String.format("%.1f", logMin), String.format("%.1f", logMax),
                    blocksProcessed, snowSet, snowRemoved,
                    surfaceGroundBlocks, edgeGroundBlocks, edgeSnowSet,
                    loadingChunk);
                DEBUG_CHUNK_COUNT++;
            }

            if (chunk instanceof LevelChunk levelChunk) {
                keeper.updateAndSend(level, levelChunk);
                weatherKeeper.updateAndSend(level, levelChunk);
            }
        } catch (Exception e) {
            LOG.warn("[estfc:diag] forceChunkUpdateHeight failed: {}", e.toString());
        }
    }

    /**
     * TFC 温度 → 雪深 (0-100)。委托给 WeatherBridge.tempToSnowDepth（所有地方统一公式）。
     */
    @Unique
    private static int estfc$sampleSnowDepth(ClimateModel model, ServerLevel level, BlockPos pos) {
        float temp = model.getInstantTemperature(level, pos);
        return WeatherBridge.tempToSnowDepth(temp);
    }
}
