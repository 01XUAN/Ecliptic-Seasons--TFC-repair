package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 修复 SnowyStatusKeeper.tickChunk 对地面方块的雪处理。
 *
 * <h3>问题 #1: FLAG_NONE 强制跳转到 SNOW_MELT</h3>
 * tickChunk 第 157-160 行：
 * <pre>
 *   int flag = MapChecker.getDefaultBlockTypeFlag(state);
 *   snowStatus = flag == FLAG_NONE ? SNOW_MELT : getSnowStatus(...);
 * </pre>
 * TFC 地面方块 flag=FLAG_NONE → 直接赋 SNOW_MELT → WeatherManagerMixin
 * 对 getSnowStatus 的拦截完全被绕过 → 地面雪被无条件移除。
 * 修复：固体地面方块返回非零 flag → 走 getSnowStatus → TFC 温度正确判断。
 *
 * <h3>问题 #2: biome 级雪深阈值</h3>
 * tickChunk 用 biome 级缓存深度 (getSnowDepthAtBiome) 做 per-block 雪决策。
 * TFC 温度是 (纬度,海拔) 的位置函数，biome 级缓存完全不适用。
 * 修复：
 * <ul>
 *   <li>SNOW 分支 (ordinal 0): 返回 100 → 阈值恒通过 → getSnowStatus 决定</li>
 *   <li>SNOW_MELT 分支 (ordinal 1): 返回 0 → 阈值恒通过 → getSnowStatus 决定</li>
 * </ul>
 * getSnowStatus 已被 WeatherManagerMixin 拦截，基于 TFC per-block 温度返回正确状态。
 */
@Mixin(value = SnowyStatusKeeper.class, remap = false)
public abstract class SnowyStatusKeeperMixin {

    @Unique
    private static final Logger ESTFC_LOG = LoggerFactory.getLogger("estfc");

    @Unique
    private static boolean TICK_FLAG_LOGGED = false;

    /**
     * 修复 #1: 拦截 getDefaultBlockTypeFlag，固体地面方块返回非零 flag。
     * 防止 tickChunk 中 flag==FLAG_NONE 时直接跳到 SNOW_MELT 绕过 getSnowStatus。
     */
    @WrapOperation(
        method = "tickChunk",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/map/MapChecker;getDefaultBlockTypeFlag(Lnet/minecraft/world/level/block/state/BlockState;)I")
    )
    private int estfc$fixGroundFlagInTickChunk(BlockState state, Operation<Integer> original) {
        int flag = original.call(state);
        if (flag == MapChecker.FLAG_NONE && state.blocksMotion()) {
            if (!TICK_FLAG_LOGGED) {
                TICK_FLAG_LOGGED = true;
                ESTFC_LOG.info("[estfc:tickChunk] flag override active — FLAG_NONE+solid → 1 for {}",
                    state.getBlock().builtInRegistryHolder().key().location());
            }
            return 1; // 非零 → 走 getSnowStatus 而不是强制 SNOW_MELT
        }
        return flag;
    }

    /**
     * 修复 #2a: 替换 SNOW 分支中的 getSnowDepthAtBiome 调用 (ordinal 0)。
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
     * 修复 #2b: 替换 SNOW_MELT 分支中的 getSnowDepthAtBiome 调用 (ordinal 1)。
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
