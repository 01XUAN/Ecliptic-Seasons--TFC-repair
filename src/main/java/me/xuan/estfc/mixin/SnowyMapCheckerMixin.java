package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.map.BiomeHolder;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.teamtea.eclipticseasons.common.core.snow.WeatherStatusKeeper;
import me.xuan.estfc.weather.ChunkSnowRefresh;
import me.xuan.estfc.weather.SnowDepthRules;
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
 * 具体逻辑见 {@link ChunkSnowRefresh}。
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

            ChunkSnowRefresh.ChunkContext ctx = ChunkSnowRefresh.prepare(level, chunk, model);

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

                    float colTemp = ChunkSnowRefresh.columnTemperature(ctx, surfaceHeight);
                    // 有效深度 = max(累积群系深度, 温度地板)。温度地板保证干冷（无降水→biomeDepth=0）
                    // 的地方也有覆雪；累积深度保证降雪期间逐步加厚。bug4 的"瞬间满雪"由 B1
                    // (lastStepTick 无条件推进) 修复，去掉温度地板会让寒冷地区整片无雪。
                    int colSnowDepth = SnowDepthRules.calcEffectiveDepth(colTemp, ctx.biomeSnowDepth());

                    for (int y = minY; y <= maxY; y++) {
                        checkPos.set(x, y, z);
                        BlockState state = chunk.getBlockState(checkPos);

                        if (ChunkSnowRefresh.isSkippable(state)) continue;

                        boolean hasSnow = keeper.isSnowyBlock(checkPos);

                        if (loadingChunk) {
                            ChunkSnowRefresh.applyOnLoad(keeper, checkPos, state,
                                colTemp, colSnowDepth, hasSnow);
                        } else {
                            ChunkSnowRefresh.applyMeltOnly(keeper, checkPos, colTemp, hasSnow);
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
