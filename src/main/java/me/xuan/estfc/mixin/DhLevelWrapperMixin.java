package me.xuan.estfc.mixin;

import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.teamtea.eclipticseasons.client.util.ClientCon;
import me.xuan.estfc.client.DistantSnowService;
import me.xuan.estfc.weather.ClimateQuery;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DH 远景（Distant Horizons LOD）覆雪颜色。
 *
 * <p>在 DH 生成 LOD 网格时，为低温位置的方块返回白色，
 * 让 DH 远景正确显示 TFC 覆雪。</p>
 *
 * <p><b>为什么用直接位置温度而非玩家参考温度：</b></p>
 * <ul>
 *   <li>玩家参考温度随时间变化（TFC 昼夜温差可达 10+°C），
 *       导致不同时刻生成的 LOD 区域雪量不一致 → 128×128 网格花纹</li>
 *   <li>玩家参考温度随玩家位置变化（纬度/大陆度），
 *       导致远方 LOD 区域雪量由玩家位置决定而非实际气候</li>
 *   <li>TFC {@code ClimateModel.getInstantTemperature} 是纯计算函数，
 *       基于世界种子 + 位置，不依赖区块加载状态，线程安全</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_neoforge", remap = false)
public abstract class DhLevelWrapperMixin {

    @Unique
    private static final Logger estfc$LOG = LoggerFactory.getLogger("estfc");

    /** 白色 ARGB int: 0xFFFFFFFF */
    @Unique
    private static final int WHITE_ARGB = -1;

    /** 首次调用标记 —— 确认 mixin 已生效 */
    @Unique
    private static volatile boolean estfc$applied = false;

    @Unique
    @Inject(method = "getBlockColor", at = @At("RETURN"), cancellable = true, require = 0)
    private void estfc$dhOverrideSnowColor(
            DhBlockPos blockWorldPos,
            IBiomeWrapper biome,
            FullDataSourceV2 fullDataSource,
            IBlockStateWrapper blockWrapper,
            boolean allowApiOverride,
            CallbackInfoReturnable<Integer> cir) {

        if (!estfc$applied) {
            estfc$applied = true;
            estfc$LOG.info("[estfc] DH snow color override active (direct TFC temperature)");
        }

        try {
            Level level = ClientCon.getUseLevel();
            if (level == null) return;

            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return;

            BlockPos mcPos = new BlockPos(
                    blockWorldPos.getX(), blockWorldPos.getY(), blockWorldPos.getZ());

            int biomeDepth = ClimateQuery.getBiomeDepthAt(level, mcPos);
            int depth = DistantSnowService.calcSnowDepthClient(level, model, mcPos, biomeDepth);

            if (depth <= 0) return;
            if (blockWrapper.isLiquid()) return;

            // depth 0→原始色, depth 100→纯白, 线性混合
            float factor = Math.min(depth / 100f, 1f);
            int snow = estfc$blendToWhite(cir.getReturnValue(), factor);
            cir.setReturnValue(snow);
        } catch (Exception ignored) {}
    }

    /** 将 ARGB 颜色按 factor [0,1] 向白色混合 */
    @Unique
    private static int estfc$blendToWhite(int argb, float factor) {
        if (factor <= 0f) return argb;
        if (factor >= 1f) return WHITE_ARGB;

        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        r = r + (int)((255 - r) * factor);
        g = g + (int)((255 - g) * factor);
        b = b + (int)((255 - b) * factor);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
