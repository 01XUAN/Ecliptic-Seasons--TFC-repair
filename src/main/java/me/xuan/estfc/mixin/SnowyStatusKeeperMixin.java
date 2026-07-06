package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 修复 SnowyStatusKeeper.tickChunk 中对 getSnowDepthAtBiome 的调用。
 *
 * <h3>问题</h3>
 * tickChunk 在每次随机 tick 时用 biome 级缓存深度 (getSnowDepthAtBiome)
 * 做 per-block 雪决策。TFC 温度是纬度+海拔决定的位置函数，
 * biome 级缓存完全不适用——同一 biome 在不同 Z 坐标温度完全不同。
 *
 * <h3>修复</h3>
 * 绕过 getSnowDepthAtBiome 的阈值检查：
 * <ul>
 *   <li>SNOW 分支 (ordinal 0): 返回 100 → 阈值总是通过 → getSnowStatus 决定</li>
 *   <li>SNOW_MELT 分支 (ordinal 1): 返回 0 → 阈值总是通过 → getSnowStatus 决定</li>
 * </ul>
 * getSnowStatus 已被 WeatherManagerMixin 拦截，基于 TFC per-block 温度返回正确状态。
 */
@Mixin(value = SnowyStatusKeeper.class, remap = false)
public abstract class SnowyStatusKeeperMixin {

    /**
     * 替换 SNOW 分支中的 getSnowDepthAtBiome 调用 (ordinal 0)。
     * 返回 100 → |seed%100| (0~99) < 100 恒成立 → 只要 getSnowStatus 说 SNOW 就设雪。
     */
    @WrapOperation(
        method = "tickChunk",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/biome/WeatherManager;getSnowDepthAtBiome(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/biome/Biome;)I",
            ordinal = 0)
    )
    private int estfc$replaceDepthForSnow(Level level, Biome biome, Operation<Integer> original) {
        return estfc$isTFC(level) ? 100 : original.call(level, biome);
    }

    /**
     * 替换 SNOW_MELT 分支中的 getSnowDepthAtBiome 调用 (ordinal 1)。
     * 返回 0 → |seed%100| (0~99) >= 0 恒成立 → 只要 getSnowStatus 说 SNOW_MELT 就融雪。
     */
    @WrapOperation(
        method = "tickChunk",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/biome/WeatherManager;getSnowDepthAtBiome(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/biome/Biome;)I",
            ordinal = 1)
    )
    private int estfc$replaceDepthForMelt(Level level, Biome biome, Operation<Integer> original) {
        return estfc$isTFC(level) ? 0 : original.call(level, biome);
    }

    @Unique
    private static boolean estfc$isTFC(Level level) {
        try {
            ClimateModel model = Climate.get(level);
            return model.supportsRain();
        } catch (Exception e) {
            return false;
        }
    }
}
