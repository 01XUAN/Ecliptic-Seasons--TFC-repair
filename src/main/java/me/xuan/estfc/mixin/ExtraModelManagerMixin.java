package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.xuan.estfc.WeatherBridge;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 修复客户端 ExtraModelManager.getLayer 的雪层深度计算。
 *
 * <h3>问题</h3>
 * getLayer 在第 1337 行调用 WeatherManager.getSnowDepthAtBiome(useLevel, biome)
 * ——这是 biome 级缓存深度，在 TFC 纬度制气候下完全不正确。
 * 导致温暖地区（生物群系缓存深度被玩家冷位置污染）渲染雪层，
 * 且区块边界处因 biome 深度跳变产生网格花纹。
 *
 * <h3>修复</h3>
 * 拦截 getLayer 中对 getSnowDepthAtBiome 的调用，
 * 替换为 TFC 瞬时温度 → tempToSnowDepth。
 * TFC 温度是 f(z纬度, y海拔, 时间) 的确定性函数，在客户端始终可用。
 */
@Mixin(value = ExtraModelManager.class, remap = false)
public abstract class ExtraModelManagerMixin {

    @Unique
    private static final Logger LOG = LoggerFactory.getLogger("estfc");

    @Unique
    private static boolean LOGGED_FIRST = false;

    /**
     * 替换 getLayer 中的 getSnowDepthAtBiome 调用。
     * pos 是雪层渲染的目标位置（已经被 getLayer 修改为 Y+1，
     * 即雪层所处方块上方的方块位置。TFC 海拔差 1m ≈ 0.16°C，可忽略）。
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
