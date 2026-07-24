package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.xuan.estfc.WeatherBridge;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 修复 SnowyStatusKeeper.tickChunk 中对 getSnowDepthAtBiome 的调用。
 *
 * <p>ES 用 biome 级缓存深度做 per-block 概率判断。TFC 温度是 per-position 的函数，
 * biome 级缓存完全不适用。改为用 TFC per-block 温度计算真实深度，
 * 让 tickChunk 的概率阈值（|seed%100|）基于真实位置温度自然分布。
 */
@Mixin(value = SnowyStatusKeeper.class, remap = false)
public abstract class SnowyStatusKeeperMixin {

    /**
     * 替换 SNOW 分支中的 getSnowDepthAtBiome (ordinal 0)。
     * 使用 TFC per-block 温度计算真实雪深 → 更冷的位置更容易积雪。
     */
    @WrapOperation(
        method = "tickChunk",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/biome/WeatherManager;getSnowDepthAtBiome(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/biome/Biome;)I",
            ordinal = 0)
    )
    private int estfc$replaceDepthForSnow(Level level, Biome biome, Operation<Integer> original,
                                           @Local BlockPos checkPos) {
        int depth = estfc$getPerBlockDepth(level, checkPos);
        return depth >= 0 ? depth : original.call(level, biome);
    }

    /**
     * 替换 SNOW_MELT 分支中的 getSnowDepthAtBiome (ordinal 1)。
     * 与 SNOW 分支使用相同的真实位置深度：当 getSnowStatus 返回 MELT 时
     * temp > meltTemp → depth=0 → 0 <= seedMod 恒成立 → 必定融雪。
     */
    @WrapOperation(
        method = "tickChunk",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/biome/WeatherManager;getSnowDepthAtBiome(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/biome/Biome;)I",
            ordinal = 1)
    )
    private int estfc$replaceDepthForMelt(Level level, Biome biome, Operation<Integer> original,
                                           @Local BlockPos checkPos) {
        int depth = estfc$getPerBlockDepth(level, checkPos);
        return depth >= 0 ? depth : original.call(level, biome);
    }

    /**
     * 基于 TFC per-block 温度 + biome 积雪历史返回有效雪深 (0-100)。
     * 返回 -1 表示 TFC 不可用 → 回退到 ES 原生 biome 缓存深度。
     *
     * <p>服务端已加载区块：由 tickWeatherCycle 缓慢累积的 biomeDepth 主导，
     * 不再混合瞬时温度贡献，确保积雪只在降雪期间逐渐增加。</p>
     */
    @Unique
    private static int estfc$getPerBlockDepth(Level level, BlockPos pos) {
        try {
            ClimateModel model = WeatherBridge.getModelIfActive(level);
            if (model == null) return -1;
            float temp = model.getInstantTemperature(level, pos);
            boolean precip = WeatherBridge.isPrecipitating(model, (ServerLevel) level, pos);
            int biomeDepth = WeatherBridge.getBiomeDepthAt(level, pos);
            return WeatherBridge.calcEffectiveDepth(temp, precip, biomeDepth);
        } catch (Exception e) {
            return -1;
        }
    }
}
