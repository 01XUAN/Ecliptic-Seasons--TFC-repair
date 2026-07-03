package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.biome.BiomeClimateManager;
import me.xuan.estfc.SnowDepthCache;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 覆盖 shouldSnowAtBiome — DH 远景 + 客户端渲染入口。
 *
 * <h3>三层回退策略</h3>
 * <ol>
 *   <li><b>TFC 精确温度</b> — 客户端直接调用 TFC Climate API 获取位置温度，
 *       对附近有 ChunkData 的区块最准确</li>
 *   <li><b>SnowDepthCache</b> — 从服务端 BiomeWeatherMessage 同步的群系级雪深，
 *       DH 远距离区块降级使用</li>
 *   <li><b>雪线检查</b> — 海拔超过雪线直接返回 true，兜底</li>
 * </ol>
 */
@Mixin(value = MapChecker.class, remap = false)
public abstract class MapCheckerMixin {

    @Unique
    private static final Logger ESTFC_LOG = LoggerFactory.getLogger("estfc");
    @Unique
    private static boolean LOGGED = false;

    @Inject(method = "shouldSnowAtBiome", at = @At("HEAD"), cancellable = true)
    private static void estfc$shouldSnowAtBiome(
        Level level, Biome biome, BlockState state, RandomSource random, long seed, BlockPos mcPos,
        CallbackInfoReturnable<Boolean> cir) {

        // 服务端走 ES 原生逻辑（群系列表已正确初始化）
        if (level instanceof ServerLevel) return;

        // ============ 客户端 ============

        // Tier 1: TFC 精确温度（需要 ChunkData，DH 远距离可能不可用）
        try {
            ClimateModel model = Climate.get(level);
            if (model.supportsRain()) {
                float temp = model.getInstantTemperature(level, mcPos);
                int depth = estfc$tempToSnowDepth(temp);
                if (depth > Math.abs(seed % 100)) {
                    cir.setReturnValue(true);
                    return;
                }
                if (depth == 0) {
                    // 零上且不满足雪深条件 → 无雪
                    cir.setReturnValue(false);
                    return;
                }
                // depth > 0 但 seed 不匹配 → 继续 fallback
            }
        } catch (Exception e) {
            // TFC ChunkData 不可用（DH 远距离区块），回退到缓存
        }

        // Tier 2: SnowDepthCache（服务端同步的群系级雪深）
        int biomeId = level.registryAccess().registryOrThrow(Registries.BIOME).getId(biome);
        if (biomeId >= 0 && biomeId < SnowDepthCache.DEPTHS.length) {
            int depth = SnowDepthCache.DEPTHS[biomeId] & 0xFF;
            if (depth > Math.abs(seed % 100)) {
                cir.setReturnValue(true);
                return;
            }
        }

        // Tier 3: 雪线检查
        int snowLine = BiomeClimateManager.getSnowLine(biome, false);
        if (mcPos.getY() > snowLine) {
            cir.setReturnValue(true);
            return;
        }

        cir.setReturnValue(false);
    }

    /**
     * TFC 温度 → ES 雪深 (0-100)。与 WeatherManagerMixin 逻辑一致。
     */
    @Unique
    private static int estfc$tempToSnowDepth(float temp) {
        if (temp < -10) return 100;
        if (temp <= 0)  return 20 + (int)(Math.abs(temp) * 8);
        return 0;
    }
}
