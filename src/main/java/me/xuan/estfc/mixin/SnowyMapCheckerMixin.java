package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.map.BiomeHolder;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.teamtea.eclipticseasons.common.core.snow.WeatherStatusKeeper;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.dries007.tfc.util.tracker.WorldTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
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
 * 配合 WeatherManagerMixin 覆盖的 getSnowStatus，已加载区块通过
 * tickChunk 维持雪状态，新区块通过本 mixin 即时初始化。
 * biome 级雪深同步到客户端实现全局视觉一致性。
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

            WorldTracker tracker = WorldTracker.get(level);
            if (!tracker.isWeatherEnabled()) return;

            SnowyStatusKeeper keeper = SnowyMapChecker.getSnowyStatusKeeper(chunk);
            WeatherStatusKeeper weatherKeeper = SnowyMapChecker.getWeatherStatusKeeper(chunk);

            // 性能优化：非加载且无雪 → 跳过
            if (!loadingChunk && keeper.getPosMap().isEmpty()) return;

            BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();
            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();
            int maxX = chunk.getPos().getMaxBlockX();
            int maxZ = chunk.getPos().getMaxBlockZ();

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int surfaceHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    int solidHeight = chunkMap.getHeight(x, z);

                    // 每列用顶部温度代表整列的雪深（同列温差可忽略）
                    checkPos.set(x, solidHeight, z);
                    int snowDepth = estfc$tempToSnowDepth(model, level, checkPos);

                    for (int y = solidHeight; y <= surfaceHeight; y++) {
                        checkPos.set(x, y, z);
                        BlockState state = chunk.getBlockState(checkPos);
                        int flag = MapChecker.getDefaultBlockTypeFlag(state);

                        if (flag == MapChecker.FLAG_NONE) continue;

                        // 温度雪深 vs 随机种子 → 该方块是否覆雪
                        boolean shouldSnow = snowDepth > Math.abs(state.getSeed(checkPos) % 100);
                        boolean hasSnow = keeper.isSnowyBlock(checkPos);

                        if (shouldSnow && !hasSnow) {
                            keeper.set(checkPos, SnowyStatusKeeper.FLAG_SNOW);
                        } else if (!shouldSnow && hasSnow) {
                            keeper.set(checkPos, SnowyStatusKeeper.FLAG_NONE);
                        }
                    }
                }
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
     * TFC 温度 → 雪深 (0-100)。与 WeatherManagerMixin 中逻辑完全一致。
     */
    @Unique
    private static int estfc$tempToSnowDepth(ClimateModel model, ServerLevel level, BlockPos pos) {
        float temp = model.getInstantTemperature(level, pos);
        if (temp < -10) return 100;
        if (temp <= 0)  return 20 + (int)(Math.abs(temp) * 8);
        return 0;
    }
}
