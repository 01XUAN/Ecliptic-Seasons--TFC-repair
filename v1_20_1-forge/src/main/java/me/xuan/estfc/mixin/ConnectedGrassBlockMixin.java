package me.xuan.estfc.mixin;

import me.xuan.estfc.Config;
import net.dries007.tfc.common.blocks.soil.ConnectedGrassBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 禁用 TFC ConnectedGrassBlock 的连接纹理方向属性。
 * 所有 NORTH/EAST/SOUTH/WEST 强制为 false，避免连接纹理干扰覆雪渲染。
 */
@Mixin(ConnectedGrassBlock.class)
public abstract class ConnectedGrassBlockMixin {

    @Inject(method = "updateStateFromDirection", at = @At("RETURN"), cancellable = true, remap = false)
    private void estfc$clearConnection(BlockGetter worldIn, BlockPos pos, BlockState stateIn,
                                       Direction direction, CallbackInfoReturnable<BlockState> cir) {
        if (Config.tfcConnectedTexture()) return;
        // updateStateFromDirection 返回 stateIn.setValue(...)，结构上必带方向属性，无需守卫
        cir.setReturnValue(estfc$clearHorizontalConnections(cir.getReturnValue()));
    }

    @Inject(method = "updateShape", at = @At("RETURN"), cancellable = true)
    private void estfc$clearConnectionOnShapeUpdate(BlockState stateIn, Direction facing,
                                                    BlockState facingState, LevelAccessor level,
                                                    BlockPos currentPos, BlockPos facingPos,
                                                    CallbackInfoReturnable<BlockState> cir) {
        if (Config.tfcConnectedTexture()) return;
        BlockState original = cir.getReturnValue();
        // updateShape 按约定可能返回别的方块的 state（如第三方子类返回 AIR），守卫必需
        if (original.hasProperty(ConnectedGrassBlock.NORTH)) {
            cir.setReturnValue(estfc$clearHorizontalConnections(original));
        }
    }

    @Unique
    private static BlockState estfc$clearHorizontalConnections(BlockState state) {
        return state.setValue(ConnectedGrassBlock.NORTH, false)
                    .setValue(ConnectedGrassBlock.EAST,  false)
                    .setValue(ConnectedGrassBlock.SOUTH, false)
                    .setValue(ConnectedGrassBlock.WEST,  false);
    }
}
