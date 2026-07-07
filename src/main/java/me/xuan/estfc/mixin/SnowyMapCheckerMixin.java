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
 * 覆雪判断与远端视图（MapCheckerMixin）使用完全相同的公式：
 * snowDepth > |state.getSeed(pos) % 100|，保证近景远景一致。
 */
@Mixin(value = SnowyMapChecker.class, remap = false)
public abstract class SnowyMapCheckerMixin {

    @Unique
    private static final Logger LOG = LoggerFactory.getLogger("estfc");

    @Unique
    private static boolean DEBUG_DONE = false;

    @Unique
    private static final int DEBUG_COLUMNS = 5; // log first N columns per first chunk

    @Inject(method = "forceChunkUpdateHeight", at = @At("RETURN"))
    private static void estfc$afterForceChunkUpdate(
        ServerLevel level, ChunkAccess chunk, ChunkInfoMap chunkMap,
        @Nullable BiomeHolder biomeHolder, boolean loadingChunk, CallbackInfo ci) {

        if (chunkMap == null) return;

        try {
            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return;

            // 不检查 WorldTracker.isWeatherEnabled()——
            // 首次加载时 WorldTracker 可能未初始化 → 会导致我们被跳过
            // → ES 原生逻辑用 biome 级深度处理边缘方块 → 边缘无雪
            // TFC 温度本身不依赖 WorldTracker，直接计算即可

            // 安全检查：确保 TFC ChunkData 已就绪（非 EMPTY 哨兵）
            // 在服务器首次生成区块时，ChunkData 可能尚未完整填充
            ChunkData chunkData = ChunkData.get(level, new BlockPos(
                chunk.getPos().getMinBlockX(), 64, chunk.getPos().getMinBlockZ()));
            if (chunkData == ChunkData.EMPTY) return;

            SnowyStatusKeeper keeper = SnowyMapChecker.getSnowyStatusKeeper(chunk);
            WeatherStatusKeeper weatherKeeper = SnowyMapChecker.getWeatherStatusKeeper(chunk);

            BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();
            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();
            int maxX = chunk.getPos().getMaxBlockX();
            int maxZ = chunk.getPos().getMaxBlockZ();

            boolean firstChunkLog = !DEBUG_DONE;
            int colLogged = 0;
            int chunkStartX = chunk.getPos().getMinBlockX();
            int chunkStartZ = chunk.getPos().getMinBlockZ();

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int surfaceHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    int solidHeight = chunkMap.getHeight(x, z);

                    int minY = Math.min(solidHeight, surfaceHeight);
                    int maxY = Math.max(solidHeight, surfaceHeight);

                    // 诊断：检查区块边缘（x 在边界 3 格以内）或 solidHeight≠surfaceHeight
                    boolean edgeColumn = x <= minX + 2 || x >= maxX - 2
                        || z <= minZ + 2 || z >= maxZ - 2;
                    boolean heightMismatch = solidHeight != surfaceHeight;
                    boolean logThisCol = firstChunkLog && colLogged < DEBUG_COLUMNS
                        && (edgeColumn || heightMismatch);

                    if (logThisCol) {
                        LOG.info("[estfc:snowCol] chunk({},{}) pos({},{}) solidH={} surfH={} minY={} maxY={} edge={} mismatch={}",
                            chunkStartX >> 4, chunkStartZ >> 4,
                            x, z, solidHeight, surfaceHeight, minY, maxY,
                            edgeColumn, heightMismatch);
                    }

                    int yLogged = 0;
                    for (int y = minY; y <= maxY; y++) {
                        checkPos.set(x, y, z);
                        BlockState state = chunk.getBlockState(checkPos);
                        int flag = MapChecker.getDefaultBlockTypeFlag(state);

                        // TFC 地面方块（泥土/草/石头）在 ES 中 flag=FLAG_NONE(0)，
                        // 因为它们没有注册 SnowDefinition。ES 原版靠 isAboveSnowLine
                        // 给"足够冷"的地面覆雪，但接近 0°C 的地区 isAboveSnowLine=false
                        // → 地面永远无雪。
                        // 修复：固体方块即使 flag=0 也要基于 TFC 温度判断覆雪。
                        // 只跳过空气/流体等非固体（blocksMotion=false）。
                        if (flag == MapChecker.FLAG_NONE && !state.blocksMotion()) continue;

                        // 每个 Y 独立取样温度，与 DH shouldSnowAtBiome 保持一致
                        int snowDepth = estfc$sampleSnowDepth(model, level, checkPos);

                        // 统一的覆雪判断公式：depth > |seed % 100|
                        int seedMod = Math.abs((int)(state.getSeed(checkPos) % 100));
                        boolean shouldSnow = snowDepth > seedMod;
                        boolean hasSnow = keeper.isSnowyBlock(checkPos);

                        if (logThisCol && yLogged < 3) {
                            LOG.info("[estfc:snowBlock] ({},{},{}) flag={} depth={} seed%100={} should={} has={} state={}",
                                x, y, z, flag, snowDepth, seedMod, shouldSnow, hasSnow,
                                state.getBlock().builtInRegistryHolder().key().location());
                            yLogged++;
                        }

                        if (shouldSnow && !hasSnow) {
                            keeper.set(checkPos, SnowyStatusKeeper.FLAG_SNOW);
                        } else if (!shouldSnow && hasSnow) {
                            keeper.set(checkPos, SnowyStatusKeeper.FLAG_NONE);
                        }
                    }

                    if (logThisCol) colLogged++;
                }
            }

            if (firstChunkLog && !DEBUG_DONE) {
                DEBUG_DONE = true;
                LOG.info("[estfc:snowCol] debug done for chunk({},{}) — logged {} columns",
                    chunkStartX >> 4, chunkStartZ >> 4, colLogged);
            }

            if (chunk instanceof LevelChunk levelChunk) {
                keeper.updateAndSend(level, levelChunk);
                weatherKeeper.updateAndSend(level, levelChunk);
            }
        } catch (Exception e) {
            // TFC 不可用时跳过
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
