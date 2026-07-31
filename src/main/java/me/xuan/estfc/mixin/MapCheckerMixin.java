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
 * 客户端 {@code shouldSnowAtBiome} — voxy/DH 未加载区块雪判断。
 * 用 TFC per-position 温度替换 ES biome 缓存。
 */
@Mixin(value = MapChecker.class, remap = false)
public abstract class MapCheckerMixin {

    @Inject(method = "shouldSnowAtBiome", at = @At("HEAD"), cancellable = true)
    private static void estfc$shouldSnowAtBiome(
        Level level, Biome biome, BlockState state, RandomSource random, long seed, BlockPos mcPos,
        CallbackInfoReturnable<Boolean> cir) {

        if (level instanceof ServerLevel) return;

        ClimateModel model = Climate.get(level);
        if (!model.supportsRain()) return;

        int biomeDepth = ClimateQuery.getBiomeDepthAt(level, mcPos);
        int depth = DistantSnowService.calcSnowDepthClient(level, model, mcPos, biomeDepth);
        cir.setReturnValue(SnowDepthRules.shouldSnow(depth, seed));
    }
}
