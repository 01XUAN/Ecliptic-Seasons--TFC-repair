package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.teamtea.eclipticseasons.common.core.snow.WeatherStatusKeeper;
import me.xuan.estfc.Config;
import me.xuan.estfc.weather.BiomeCoverageCache;
import me.xuan.estfc.weather.BiomeSnowSeed;
import me.xuan.estfc.weather.ClimateQuery;
import me.xuan.estfc.weather.SnowDepthRules;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SnowyStatusKeeper.tickChunk 的注入：节流 + 深度替换。
 *
 * <p>采样坐标不碰：曾怀疑 vanilla 选点 LCG（{@code randValue * 3 + 1013904223}，x 偏移
 * {@code (randValue >> 2) & 15} 周期仅 32）经节流后会让同一区块反复只采到固定几列，
 * 实测该退化存在，但**不是「概率覆雪集中在一角」的原因**（换成均匀取列后现象不变），
 * 且会白扰动 {@code level.random} 这条全局随机流，故保持原样。</p>
 */
@Mixin(value = SnowyStatusKeeper.class, remap = false)
public abstract class SnowyStatusKeeperMixin {

    /**
     * 区块内列游标（0..255）。每次真正执行 tickChunk 时取下一列，扫完 256 列再回绕。
     * 随机选列会在雪多后被大量重复命中，覆雪逼近 C 的速度越来越慢；游标保证一轮内
     * 每列只被检查一次，新增/清除只发生在尚未处理的位置。
     */
    @Unique
    private int estfc$columnCursor;
    /** 每个区块固定一份随机列序：一轮不重复，但空间上分散，避免融雪按行/条带推进。 */
    @Unique
    private int[] estfc$columnOrder;

    /** 把 ES 传入的随机采样列改成区块内顺序列；y 仍由 tickChunk 自己按列计算。 */
    @ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true)
    private BlockPos estfc$useColumnCursor(BlockPos checkPos,
                                           @Local(argsOnly = true) ServerLevel level,
                                           @Local(argsOnly = true) ChunkPos chunkPos) {
        // 被节流取消的调用不推进游标
        if (level.getGameTime() % Config.tickChunkIntervalTicks() != 0) return checkPos;

        int index = estfc$columnCursor;
        estfc$columnCursor = (index + 1) & 0xFF;
        if (estfc$columnOrder == null) {
            estfc$columnOrder = estfc$shuffledColumns(chunkPos);
        }
        int column = estfc$columnOrder[index];
        int x = chunkPos.getMinBlockX() + (column & 15);
        int z = chunkPos.getMinBlockZ() + (column >> 4);
        return new BlockPos(x, checkPos.getY(), z);
    }

    @Unique
    private static int[] estfc$shuffledColumns(ChunkPos pos) {
        int[] order = new int[256];
        for (int i = 0; i < order.length; i++) order[i] = i;
        RandomSource random = RandomSource.create(pos.toLong() ^ 0x9E3779B97F4A7C15L);
        for (int i = order.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        return order;
    }

    /** 按 tickChunkIntervalTicks 节流，取消 ES 原生每 tick 调用 */
    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void estfc$throttle(ServerLevel level, LevelChunk chunk, ChunkPos chunkPos, BlockPos checkPos, ChunkInfoMap chunkInfoMap, WeatherStatusKeeper weatherStatusKeeper, CallbackInfo ci) {
        if (level.getGameTime() % Config.tickChunkIntervalTicks() != 0) {
            ci.cancel();
        }
    }

    /**
     * 把 ES tickChunk 里两处 getSnowDepthAtBiome 换成当前群系覆盖率 C：
     * 加雪/雪停都补齐到 C，融雪返回 0；没有 C 时才回退降水目标。
     *
     * <p>这样已加载区块、新区块、DH、voxy 用同一个覆盖率，不需要整区块重刷。</p>
     */
    @WrapOperation(
        method = "tickChunk",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/biome/WeatherManager;getSnowDepthAtBiome(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/biome/Biome;)I")
    )
    private int estfc$replaceDepthForMelt(Level level, Biome biome, Operation<Integer> original,
                                           @Local BlockPos checkPos) {
        int depth = estfc$getPerBlockDepth(level, biome, checkPos);
        return depth >= 0 ? depth : original.call(level, biome);
    }

    /**
     * 采样点的目标概率：优先用服务端已同步的群系覆盖率 C（降水/雪停/融雪都一致），
     * 这样已加载区块只补齐到 C，不会在降水时用 100 抢跑，也不会在雪停后停在半路。
     * 没有 C 时才退回降水目标。
     */
    @Unique
    private static int estfc$getPerBlockDepth(Level level, Biome biome, BlockPos pos) {
        try {
            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) return -1;
            if (!(level instanceof ServerLevel serverLevel)) return -1;

            BlockPos samplePos = new BlockPos(pos.getX(),
                level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()), pos.getZ());
            BiomeSnowSeed.ensureSeeded(serverLevel, biome, samplePos);

            float temp = model.getInstantTemperature(level, pos);
            if (temp > Config.snowMeltTemp()) return 0;

            float rainfall = model.getAverageRainfall(level, pos);
            Float coverage = BiomeCoverageCache.getDisplay(serverLevel, biome);
            if (coverage != null) {
                return SnowDepthRules.depthFromCoverage(temp, Math.round(coverage), rainfall);
            }

            if (!ClimateQuery.isPrecipitating(serverLevel, pos)) return -1;
            return SnowDepthRules.depthFromCoverage(temp, 0, rainfall);
        } catch (Exception e) {
            return -1;
        }
    }
}
