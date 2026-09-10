package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import me.xuan.estfc.client.DistantSnowService;
import me.xuan.estfc.weather.ClimateQuery;
import me.xuan.estfc.weather.SnowDepthRules;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端 {@code shouldSnowAtBiome} — voxy 未加载区块雪判断。
 * 用 TFC per-position 温度替换 ES biome 缓存。
 */
@Mixin(value = MapChecker.class, remap = false)
public abstract class MapCheckerMixin {

    @Inject(method = "shouldSnowAtBiome", at = @At("HEAD"), cancellable = true)
    private static void estfc$shouldSnowAtBiome(
        Level level, Biome biome, BlockState state, RandomSource random, long seed, BlockPos mcPos,
        CallbackInfoReturnable<Boolean> cir) {

        try {
            if (level instanceof ServerLevel) return;

            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) return;

            // 用调用方传入的群系，不要按 mcPos 回查：远景位置的区块未加载，
            // level.getBiome(pos) 只会返回占位群系（雪深恒 0）
            int biomeDepth = ClimateQuery.getBiomeDepth(level, biome);
            int depth = DistantSnowService.calcSnowDepthClient(level, mcPos, biomeDepth);
            cir.setReturnValue(SnowDepthRules.shouldSnow(depth, seed));
        } catch (Exception ignored) {
            // 不设返回值直接返回，让 ES 原生路径接管；
            // 本方法跑在 voxy worker 线程上，异常逃逸会破坏对方流水线
        }
    }
}
