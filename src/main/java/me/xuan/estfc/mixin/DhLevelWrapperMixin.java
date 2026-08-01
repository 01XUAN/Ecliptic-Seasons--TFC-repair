package me.xuan.estfc.mixin;

import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.teamtea.eclipticseasons.client.util.ClientCon;
import me.xuan.estfc.client.DistantSnowService;
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
 * <p>在 DH 生成 LOD 网格时，为覆雪的方块返回白色，让 DH 远景正确显示 TFC 覆雪。</p>
 *
 * <p><b>温度公式（与 voxy 远景一致）：</b></p>
 * <ul>
 *   <li>TFC {@code getInstantTemperature} <b>不是</b>纯计算函数：它内含每日随机/昼夜项
 *       （±4°C，随机项只按游戏日做种子、跨天跳变）且依赖 ChunkData（未加载返回假 10°C 基线），
 *       未加载位置需经 {@code TfcUnloadedTempEstimator} 修正基线。</li>
 *   <li>用该温度做 0°C 硬门 + {@code max(biomeDepth, tempToSnowDepth(temp))}，与已加载视图、
 *       voxy 远景共用同一公式——不要整体换成 biomeDepth 纯累积，否则冬天全图满雪。</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_neoforge", remap = false)
public abstract class DhLevelWrapperMixin {

    @Unique
    private static final Logger estfc$LOG = LoggerFactory.getLogger("estfc");

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

            // 最便宜的守卫提到分配对象之前
            if (blockWrapper.isLiquid()) return;

            BlockPos mcPos = new BlockPos(
                    blockWorldPos.getX(), blockWorldPos.getY(), blockWorldPos.getZ());

            int color = DistantSnowService.snowColorFor(level, model, mcPos, cir.getReturnValue());
            if (color != cir.getReturnValue()) cir.setReturnValue(color);
        } catch (Exception ignored) {}
    }
}
