package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.biome.BiomeClimateManager;
import me.xuan.estfc.SnowDepthCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 覆盖 shouldSnowAtBiome — DH 远景渲染的核心入口。
 *
 * <p>ES 原生的 shouldSnowAtBiome 调用 getSnowDepthAtBiome → getBiomeWeather
 * → IBiomeTagHolder.bindId 查表。客户端 biome 对象（DH 传入）可能 bindId
 * 未初始化，导致雪深返回 0。</p>
 *
 * <p>服务端走 ES 原生逻辑（biome 列表正常初始化），客户端使用 SnowDepthCache
 * （从 BiomeWeatherMessage 同步的缓存值）。</p>
 */
@Mixin(value = MapChecker.class, remap = false)
public abstract class MapCheckerMixin {

    @Inject(method = "shouldSnowAtBiome", at = @At("HEAD"), cancellable = true)
    private static void estfc$shouldSnowAtBiome(
        Level level, Biome biome, BlockState state, RandomSource random, long seed, BlockPos mcPos,
        CallbackInfoReturnable<Boolean> cir) {

        // 服务端走 ES 原生逻辑
        if (level instanceof ServerLevel) return;

        // 客户端：雪线检查
        int snowLine = BiomeClimateManager.getSnowLine(biome, false);
        if (mcPos.getY() > snowLine) {
            cir.setReturnValue(true);
            return;
        }

        // 客户端：从缓存获取 biome 雪深
        int biomeId = level.registryAccess().registryOrThrow(Registries.BIOME).getId(biome);
        if (biomeId >= 0 && biomeId < SnowDepthCache.DEPTHS.length) {
            int depth = SnowDepthCache.DEPTHS[biomeId] & 0xFF;
            if (depth > Math.abs(seed % 100)) {
                cir.setReturnValue(true);
                return;
            }
        }
        // 缓存无数据 → false
        cir.setReturnValue(false);
    }
}
