package me.xuan.estfc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.compat.distanthorizons.DHTool;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 修复 DH 远景地面无雪的问题。
 *
 * <h3>根因</h3>
 * DHTool.computeBaseColor 在 shouldSnowAtBiome 返回 true 后，遍历 LOD
 * 柱体寻找应染白的方块。判断条件是：
 * <pre>
 *   bottomY == dhBlockPos.getY()  AND
 *   (getDefaultBlockTypeFlag(state) != 0          ← 地面方块 flag=0，不通过!
 *    || (!isSolid() && !isLiquid()))              ← 地面方块 isSolid=true，不通过!
 * </pre>
 * 地面方块（泥土/草/石头）flag=0 且 isSolid=true → 两个条件都不满足
 * → 不返回 MapColor.SNOW → DH 远景地面保持原色（棕色/绿色）而非白色。
 *
 * <p>树叶/植物方块 flag≠0 → 条件通过 → 正常覆雪。这就是为什么"树叶覆雪正常，
 * 地面无雪"。</p>
 *
 * <h3>修复</h3>
 * 对 solid 方块（blocksMotion=true），将 flag 从 0 提升为 1，
 * 使其通过 DHTool 的 flag!=0 检查。
 */
@Mixin(value = DHTool.class, remap = false)
public abstract class DHToolMixin {

    @WrapOperation(
            method = "computeBaseColor",
            at = @At(value = "INVOKE",
                    target = "Lcom/teamtea/eclipticseasons/common/core/map/MapChecker;getDefaultBlockTypeFlag(Lnet/minecraft/world/level/block/state/BlockState;)I")
    )
    private static int estfc$fixGroundSnowInDH(BlockState state, Operation<Integer> original) {
        int flag = original.call(state);
        // 固体地面方块在 ES 中 flag=FLAG_NONE(0)，导致 DH 不给它们染雪色。
        // blocksMotion()=true 表示有碰撞体积的实体方块（泥土/石头等），
        // 不包括水、空气等。返回非零值使其通过 DHTool 的 flag!=0 检查。
        if (flag == MapChecker.FLAG_NONE && state.blocksMotion()) {
            return 1;
        }
        return flag;
    }
}
