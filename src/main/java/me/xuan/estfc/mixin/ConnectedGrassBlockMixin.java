package me.xuan.estfc.mixin;

import net.dries007.tfc.common.blocks.soil.ConnectedGrassBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 禁用 TFC ConnectedGrassBlock 的连接纹理方向属性。
 * 所有 NORTH/EAST/SOUTH/WEST 强制为 false，避免连接纹理干扰覆雪渲染。
 */
@Mixin(value = ConnectedGrassBlock.class, remap = false)
public abstract class ConnectedGrassBlockMixin {

    @Inject(method = "updateStateFromDirection", at = @At("RETURN"), cancellable = true)
    private void estfc$clearConnection(BlockGetter worldIn, BlockPos pos, BlockState stateIn,
                                       Direction direction, CallbackInfoReturnable<BlockState> cir) {
        cir.setReturnValue(cir.getReturnValue()
                .setValue(ConnectedGrassBlock.NORTH, false)
                .setValue(ConnectedGrassBlock.EAST, false)
                .setValue(ConnectedGrassBlock.SOUTH, false)
                .setValue(ConnectedGrassBlock.WEST, false));
    }

    @Inject(method = "updateShape", at = @At("RETURN"), cancellable = true)
    private void estfc$clearConnectionOnShapeUpdate(BlockState stateIn, Direction facing,
                                                    BlockState facingState, LevelAccessor level,
                                                    BlockPos currentPos, BlockPos facingPos,
                                                    CallbackInfoReturnable<BlockState> cir) {
        BlockState original = cir.getReturnValue();
        if (original.hasProperty(ConnectedGrassBlock.NORTH)) {
            cir.setReturnValue(original
                    .setValue(ConnectedGrassBlock.NORTH, false)
                    .setValue(ConnectedGrassBlock.EAST, false)
                    .setValue(ConnectedGrassBlock.SOUTH, false)
                    .setValue(ConnectedGrassBlock.WEST, false));
        }
    }
}
