package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.xuan.estfc.WeatherBridge;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 修复客户端 ExtraModelManager.getLayer 的雪层深度计算。
 *
 * <h3>问题 #1</h3>
 * getLayer 调用 WeatherManager.getSnowDepthAtBiome(level, biome)
 * —— biome 级缓存深度，在 TFC 纬度制气候下完全不正确。
 *
 * <h3>问题 #2</h3>
 * getLayer 第 1316 行检查 getDefaultBlockTypeFlag(state) <= FLAG_NONE → return 0。
 * TFC 地面方块（泥土/草/石头）flag=0 → 直接返回 0，永远走不到雪深计算。
 *
 * <h3>修复</h3>
 * 1. 拦截 getDefaultBlockTypeFlag → 固体方块返回非零 → 通过 flag 检查
 * 2. 拦截 getSnowDepthAtBiome → 替换为 TFC 瞬时温度 → tempToSnowDepth
 */
@Mixin(value = ExtraModelManager.class, remap = false)
public abstract class ExtraModelManagerMixin {

    @Unique
    private static final Logger LOG = LoggerFactory.getLogger("estfc");

    @Unique
    private static boolean LOGGED_FIRST = false;

    /**
     * 修复 #1: 让固体地面方块通过 getLayer 的 flag <= FLAG_NONE 检查。
     * TFC 地面方块 flag=0(FLAG_NONE) → 原版直接 return 0 → 客户端不渲染雪层。
     */
    @WrapOperation(
        method = "getLayer",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/map/MapChecker;getDefaultBlockTypeFlag(Lnet/minecraft/world/level/block/state/BlockState;)I")
    )
    private static int estfc$fixGroundFlagInGetLayer(BlockState state, Operation<Integer> original) {
        int flag = original.call(state);
        if (flag == MapChecker.FLAG_NONE && state.blocksMotion()) {
            return 1; // 非零 → 通过 getLayer 的 flag<=0 检查
        }
        return flag;
    }

    /**
     * 修复 #2: 替换 biome 级雪深为 TFC 温度 → 雪深。
     */
    @WrapOperation(
        method = "getLayer",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/biome/WeatherManager;getSnowDepthAtBiome(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/biome/Biome;)I")
    )
    private static int estfc$replaceDepthForLayer(Level level, Biome biome, Operation<Integer> original,
                                                   BlockAndTintGetter blockAndTintGetter, BlockPos.MutableBlockPos pos) {
        try {
            ClimateModel model = Climate.get(level);
            if (model.supportsRain()) {
                float temp = model.getInstantTemperature(level, pos);
                int depth = WeatherBridge.tempToSnowDepth(temp);

                if (!LOGGED_FIRST) {
                    LOGGED_FIRST = true;
                    LOG.info("[estfc:getLayer] TFC depth={} temp={} at ({},{},{})",
                        depth, temp, pos.getX(), pos.getY(), pos.getZ());
                }
                return depth;
            }
        } catch (Exception e) {
            if (!LOGGED_FIRST) {
                LOGGED_FIRST = true;
                LOG.warn("[estfc:getLayer] TFC unavailable, falling back to biome depth", e);
            }
        }
        return original.call(level, biome);
    }
}
