package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import me.xuan.estfc.client.SnowTextureSwapModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 覆雪模型直接复用 TFC 原方块模型结构，仅替换贴图为 estfc 覆雪贴图。
 * SnowTextureSwapModel 对每个 quad 把 tfc: 纹理替换为 estfc:，
 * 且 BakedQuadRetexturedAndReUV 强制 tintindex=-1 → 雪不着色。
 */
@Mixin(value = ExtraModelManager.class, remap = false)
public abstract class SnowModelMixin {

    @Inject(method = "getSnowyModel", at = @At("TAIL"), cancellable = true)
    private static void estfc$swapSnowModel(
        BlockState state, BlockState snowState, int flag, int offset,
        CallbackInfoReturnable<BakedModel> cir) {

        BakedModel snowModel = cir.getReturnValue();
        if (snowModel == null || state == null) return;
        if (!isTfcBlock(state)) return;

        BlockModelShaper shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();
        BakedModel tfcModel = shaper.getBlockModel(state);

        if (tfcModel != null) {
            cir.setReturnValue(new SnowTextureSwapModel(tfcModel));
        }
    }

    private static boolean isTfcBlock(BlockState state) {
        ResourceLocation id = state.getBlockHolder().getKey().location();
        if (!"tfc".equals(id.getNamespace())) return false;
        String path = id.getPath();
        return path.startsWith("plant/") || path.startsWith("crop/")
            || path.startsWith("groundcover/");
    }
}
