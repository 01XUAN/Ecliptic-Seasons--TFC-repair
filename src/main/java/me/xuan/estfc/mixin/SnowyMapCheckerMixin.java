package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import com.teamtea.eclipticseasons.common.core.map.BiomeHolder;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.teamtea.eclipticseasons.common.core.snow.WeatherStatusKeeper;
import me.xuan.estfc.Config;
import me.xuan.estfc.WeatherBridge;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * 在 ES {@code forceChunkUpdateHeight} 完成后，用 TFC per-position 温度
 * 覆写雪的开关决策（比 ES 的 biome 级缓存更精确——同 biome 不同海拔温度不同）。
 */
@Mixin(value = SnowyMapChecker.class, remap = false)
public abstract class SnowyMapCheckerMixin {

    @Inject(method = "forceChunkUpdateHeight", at = @At("RETURN"))
    private static void estfc$afterForceChunkUpdate(
        ServerLevel level, ChunkAccess chunk, ChunkInfoMap chunkMap,
        @Nullable BiomeHolder biomeHolder, boolean loadingChunk, CallbackInfo ci) {

        if (chunkMap == null) return;

        try {
            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return;

            SnowyStatusKeeper keeper = SnowyMapChecker.getSnowyStatusKeeper(chunk);
            WeatherStatusKeeper weatherKeeper = SnowyMapChecker.getWeatherStatusKeeper(chunk);

            // 检测是否正在降水
            boolean precipitating = WeatherBridge.isPrecipitating(level);

            // 查找该 chunk 对应 biome 的累积雪深
            int biomeSnowDepth = WeatherBridge.getBiomeDepthAt(level,
                new BlockPos(chunk.getPos().getMiddleBlockX(), 64, chunk.getPos().getMiddleBlockZ()));

            // 取 chunk 中心地表温度作为基准（保证在已加载chunk内，避免ChunkData.EMPTY假10°C）
            int midX = chunk.getPos().getMiddleBlockX();
            int midZ = chunk.getPos().getMiddleBlockZ();
            int centerSurface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, midX, midZ);
            float baseTemp = model.getInstantTemperature(level, new BlockPos(midX, centerSurface, midZ));
            // TFC海拔递减率: 0.16225°C/block（更高=更冷）
            final float TFC_LAPSE = 0.16225f;

            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();
            int maxX = chunk.getPos().getMaxBlockX();
            int maxZ = chunk.getPos().getMaxBlockZ();
            BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int surfaceHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    int solidHeight = chunkMap.getHeight(x, z);

                    int minY = Math.min(solidHeight, surfaceHeight);
                    int maxY = Math.max(solidHeight, surfaceHeight);

                    // O(1)列温度 = chunk中心地表温度 - 海拔差×递减率
                    float colTemp = baseTemp - (surfaceHeight - centerSurface) * TFC_LAPSE;
                    int colSnowDepth = WeatherBridge.calcEffectiveDepth(colTemp, precipitating, biomeSnowDepth);

                    for (int y = minY; y <= maxY; y++) {
                        checkPos.set(x, y, z);
                        BlockState state = chunk.getBlockState(checkPos);

                        int flag = MapChecker.getDefaultBlockTypeFlag(state);
                        if (flag == MapChecker.FLAG_NONE && !state.blocksMotion()) continue;

                        boolean hasSnow = keeper.isSnowyBlock(checkPos);

                        if (loadingChunk) {
                            // 零上 → 不应有雪（即使全局 biomeDepth > 0）
                            int snowDepth = colTemp > Config.snowMeltTemp() ? 0 : colSnowDepth;
                            if (WeatherBridge.shouldSnow(snowDepth, state.getSeed(checkPos))) {
                                if (!hasSnow) {
                                    keeper.set(checkPos, SnowyStatusKeeper.FLAG_SNOW);
                                }
                            } else if (hasSnow) {
                                keeper.set(checkPos, SnowyStatusKeeper.FLAG_NONE);
                            }
                        } else {
                            // 方块变更/其他：只融雪
                            if (colTemp > Config.snowMeltTemp() && hasSnow) {
                                keeper.set(checkPos, SnowyStatusKeeper.FLAG_NONE);
                            }
                        }
                    }
                }
            }

            // loadingChunk 时 ES 不调用 updateAndSend，此处补上
            if (loadingChunk && chunk instanceof LevelChunk levelChunk) {
                keeper.updateAndSend(level, levelChunk);
                weatherKeeper.updateAndSend(level, levelChunk);
            }
        } catch (Exception e) {
            // TFC 不可用时跳过
        }
    }
}
