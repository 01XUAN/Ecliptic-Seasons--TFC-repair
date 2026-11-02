package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.teamtea.eclipticseasons.common.core.snow.WeatherStatusKeeper;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.xuan.estfc.Config;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.climate.ClimateModel;
import net.dries007.tfc.util.climate.OverworldClimateModel;
import net.dries007.tfc.world.chunkdata.ChunkData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 区块加载/更新时的覆雪刷新逻辑（由 SnowyMapCheckerMixin 驱动）。
 *
 * <p>区块级只取一次 TFC 瞬时温度作为「月温 + 日温」基，海拔项用 TFC 原公式逐列修正；
 * 不再 256 列各查一次 TFC，也不用"区块中心温度 + 无封顶线性外推"。</p>
 *
 * <p><b>为什么还不能只插值最终覆雪率</b>：TFC 的海拔降温在
 * {@code Helpers.adjustAverageTemperatureByElevation} 里被 {@code clamp(..., 0, 17.822f)}
 * 封顶（≈110 格海拔）。只插值最终覆雪率/不按列做封顶修正，会让每个区块从中心重新起一轮，
 * 陡坡上表现为覆雪图案重复（2026-10-05 实测：相邻区块中心高度差 21 格、温度只差 0.05°C
 * ⇒ 相邻两列差 3°C / 深度 50）。</p>
 */
public final class ChunkSnowRefresh {

    private ChunkSnowRefresh() {}

    /** TFC 海拔递减率 °C/格（与 TFC 的 0.16225 一致，仅作参考/文档用） */
    public static final float TFC_LAPSE = 0.16225f;

    /**
     * 区块级温度基：月温 + 日温在同一个 16×16 区块内视为常数；海拔项按 TFC 原公式逐列修正。
     * 这样每区块只查一次 TFC 瞬时温度，而不是 256 列各查一次。
     */
    public record ChunkTemperature(ChunkData data, ClimateModel model, float monthDaily) {
        public float at(ServerLevel level, int x, int y, int z) {
            if (data == ChunkData.EMPTY) {
                return model.getInstantTemperature(level, new BlockPos(x, y, z));
            }
            if (y > OverworldClimateModel.SEA_LEVEL) {
                float avgSea = data.getAverageSeaLevelTemp(x, z);
                return Helpers.adjustAverageTemperatureByElevation(y, avgSea, OverworldClimateModel.SEA_LEVEL)
                    + monthDaily;
            }
            // 海平面及以下：月/日温影响被 TFC 按深度衰减，保留逐列原实现
            return model.getInstantTemperature(level, new BlockPos(x, y, z));
        }
    }

    /** 一个区块刷新过程中不变的共享量。 */
    public record ChunkContext(int coverage, float rainfall, ChunkTemperature temperature) {}

    public static ChunkContext prepare(ServerLevel level, ChunkAccess chunk, ClimateModel model) {
        int midX = chunk.getPos().getMiddleBlockX();
        int midZ = chunk.getPos().getMiddleBlockZ();
        int centerSurface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, midX, midZ);
        BlockPos centerPos = new BlockPos(midX, centerSurface, midZ);

        // 从区块自身取地表群系，避免 level.getBiome 走 level.getChunk
        Biome biome = chunk.getNoiseBiome(
            QuartPos.fromBlock(midX), QuartPos.fromBlock(centerSurface), QuartPos.fromBlock(midZ)).value();
        BiomeSnowSeed.ensureSeeded(level, biome, centerPos);

        ChunkData data = ChunkData.get(chunk);
        BlockPos seaRef = new BlockPos(midX, (int) OverworldClimateModel.SEA_LEVEL, midZ);
        float monthDaily = model.getInstantTemperature(level, seaRef)
            - data.getAverageSeaLevelTemp(midX, midZ);

        float rainfall = model.getAverageRainfall(level, centerPos);
        Float coverage = BiomeCoverageCache.getDisplay(level, biome);
        int biomeDepth = coverage != null ? Math.round(coverage) : ClimateQuery.getBiomeDepth(level, biome);
        return new ChunkContext(biomeDepth, rainfall, new ChunkTemperature(data, model, monthDaily));
    }

    /**
     * 整区块重算：区块加载和雪深变化后的刷新共用。
     * loadingChunk=true 时按当前雪深加雪/除雪；false 时只融雪。
     */
    public static void refreshChunk(ServerLevel level, ChunkAccess chunk, ChunkInfoMap chunkMap, boolean loadingChunk) {
        try {
            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) return;

            SnowyStatusKeeper keeper = SnowyMapChecker.getSnowyStatusKeeper(chunk);
            WeatherStatusKeeper weatherKeeper = SnowyMapChecker.getWeatherStatusKeeper(chunk);
            if (keeper == null || weatherKeeper == null) return;

            ChunkContext ctx = prepare(level, chunk, model);
            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();
            BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();

            float[] colTemps = new float[256];
            int[] colSnowDepths = new int[256];
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = minX + dx;
                    int z = minZ + dz;
                    int surfaceHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    float colTemp = ctx.temperature().at(level, x, surfaceHeight, z);
                    int index = dx + dz * 16;
                    colTemps[index] = colTemp;
                    colSnowDepths[index] = SnowDepthRules.depthFromCoverage(
                        colTemp, ctx.coverage(), ctx.rainfall());
                }
            }

            // ES 刚扫描过整列并把候选方块写进 posMap；只重算这些位置，
            // 不再逐列 solidHeight..WORLD_SURFACE 调 chunk.getBlockState。
            Long2IntLinkedOpenHashMap posMap = keeper.getPosMap();
            if (!posMap.isEmpty()) {
                LongArrayList positions = new LongArrayList(posMap.keySet());
                for (long packed : positions) {
                    checkPos.set(BlockPos.of(packed));
                    int x = checkPos.getX();
                    int z = checkPos.getZ();
                    if (x < minX || x >= minX + 16 || z < minZ || z >= minZ + 16) continue;

                    BlockState state = chunk.getBlockState(checkPos);
                    if (state.isAir() || isSkippable(state)) continue;

                    int index = (x - minX) + (z - minZ) * 16;
                    boolean hasSnow = keeper.isSnowyBlock(checkPos);
                    if (loadingChunk) {
                        applyOnLoad(keeper, checkPos, state, colTemps[index], colSnowDepths[index], hasSnow);
                    } else {
                        applyMeltOnly(keeper, checkPos, colTemps[index], hasSnow);
                    }
                }
            } else {
                scanChunk(level, chunk, chunkMap, keeper, ctx, loadingChunk, colTemps, colSnowDepths);
            }

            if (loadingChunk && chunk instanceof LevelChunk levelChunk) {
                keeper.updateAndSend(level, levelChunk);
                weatherKeeper.updateAndSend(level, levelChunk);
            }
        } catch (Exception ignored) {
            // TFC/ES 不可用时跳过
        }
    }

    /** posMap 为空时（ES 未记录候选方块）退回逐列扫描。 */
    private static void scanChunk(ServerLevel level, ChunkAccess chunk, ChunkInfoMap chunkMap,
                                  SnowyStatusKeeper keeper, ChunkContext ctx, boolean loadingChunk,
                                  float[] colTemps, int[] colSnowDepths) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int maxX = chunk.getPos().getMaxBlockX();
        int maxZ = chunk.getPos().getMaxBlockZ();
        int worldMin = chunk.getMinBuildHeight();
        int worldMax = chunk.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int surfaceHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                int solidHeight = chunkMap.getHeight(x, z);
                int minY = Math.min(Math.max(solidHeight, worldMin), worldMax);
                int maxY = Math.min(Math.max(surfaceHeight, worldMin), worldMax);
                if (maxY < minY) maxY = minY;

                int index = (x - minX) + (z - minZ) * 16;
                float colTemp = colTemps[index];
                int colSnowDepth = colSnowDepths[index];
                for (int y = minY; y <= maxY; y++) {
                    checkPos.set(x, y, z);
                    BlockState state = chunk.getBlockState(checkPos);
                    if (isSkippable(state)) continue;

                    boolean hasSnow = keeper.isSnowyBlock(checkPos);
                    if (loadingChunk) {
                        applyOnLoad(keeper, checkPos, state, colTemp, colSnowDepth, hasSnow);
                    } else {
                        applyMeltOnly(keeper, checkPos, colTemp, hasSnow);
                    }
                }
            }
        }
    }

    /** 既非地图标记方块也不阻挡移动 → 跳过。 */
    public static boolean isSkippable(BlockState state) {
        int flag = MapChecker.getDefaultBlockTypeFlag(state);
        return flag == MapChecker.FLAG_NONE && !state.blocksMotion();
    }

    /**
     * 区块加载：按有效雪深决定加雪/除雪。
     *
     * @param colSnowDepth 当前覆盖率（温度高于融雪温度时为 0）。
     */
    public static void applyOnLoad(SnowyStatusKeeper keeper, BlockPos checkPos, BlockState state,
                                   float colTemp, int colSnowDepth, boolean hasSnow) {
        // 零上 → 不应有雪（即使全局 biomeDepth > 0）
        int snowDepth = colTemp > Config.snowMeltTemp() ? 0 : colSnowDepth;
        if (SnowDepthRules.shouldSnow(snowDepth, state.getSeed(checkPos))) {
            if (!hasSnow) {
                keeper.set(checkPos, SnowyStatusKeeper.FLAG_SNOW);
            }
        } else if (hasSnow) {
            keeper.set(checkPos, SnowyStatusKeeper.FLAG_NONE);
        }
    }

    /** 方块变更/其他：只融雪。 */
    public static void applyMeltOnly(SnowyStatusKeeper keeper, BlockPos checkPos,
                                     float colTemp, boolean hasSnow) {
        if (colTemp > Config.snowMeltTemp() && hasSnow) {
            keeper.set(checkPos, SnowyStatusKeeper.FLAG_NONE);
        }
    }
}
