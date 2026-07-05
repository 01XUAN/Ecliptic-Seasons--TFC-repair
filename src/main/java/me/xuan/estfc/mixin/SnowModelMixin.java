package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.client.model.bakequad.BakedQuadRetexturedAndReUV;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 覆雪模型直接复用 TFC 原方块模型结构，仅替换贴图为 estfc 覆雪贴图。
 *
 * <p>策略：每个 quad 生成两份 —
 * <ol>
 *   <li>TFC 原 quad（保留 tintindex）→ biome 着色 → 彩色草透底</li>
 *   <li>estfc 雪 quad（BakedQuadRetexturedAndReUV，强制 tintindex=-1）→ 白色雪</li>
 * </ol>
 *
 * <p>若 Python 已生成雪遮罩（透明背景），TFC 彩色草从透明区域透出，效果最佳。
 * 若未生成雪遮罩（composited 贴图），草在雪下呈贴图自带颜色（灰度）。
 *
 * <p>此方案自动继承 TFC blockstate 的全部 variants / multipart / 朝向，
 * 无需生成 model_definition 变体。
 */
@Mixin(value = ExtraModelManager.class, remap = false)
public abstract class SnowModelMixin {

    /**
     * 在 getSnowyModel 返回前注入 —— 对 TFC plant/crop 方块替换雪模型。
     */
    @Inject(method = "getSnowyModel", at = @At("TAIL"), cancellable = true)
    private static void estfc$swapSnowModel(
        BlockState state, BlockState snowState, int flag, int offset,
        CallbackInfoReturnable<BakedModel> cir) {

        BakedModel snowModel = cir.getReturnValue();
        if (snowModel == null) return;
        if (state == null) return;
        if (!isTfcPlant(state)) return;

        // 获取 TFC 原方块模型（保留全部 variants / multipart / 朝向）
        BlockModelShaper shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();
        BakedModel tfcModel = shaper.getBlockModel(state);

        if (tfcModel != null) {
            cir.setReturnValue(new SnowTextureSwapModel(tfcModel));
        }
    }

    private static boolean isTfcPlant(BlockState state) {
        ResourceLocation id = state.getBlockHolder().getKey().location();
        return "tfc".equals(id.getNamespace())
            && (id.getPath().startsWith("plant/") || id.getPath().startsWith("crop/"));
    }

    /**
     * 包装 TFC BakedModel —— 每个 quad 生成两份：
     * TFC 原 quad（tintindex 保留）+ estfc 雪 quad（tintindex=-1）。
     */
    private static class SnowTextureSwapModel implements BakedModel {
        private final BakedModel tfcModel;

        SnowTextureSwapModel(BakedModel tfcModel) { this.tfcModel = tfcModel; }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            List<BakedQuad> src = tfcModel.getQuads(state, side, rand);
            List<BakedQuad> out = new ArrayList<>(src.size() * 2);

            TextureAtlas atlas = Minecraft.getInstance()
                .getModelManager()
                .getAtlas(TextureAtlas.LOCATION_BLOCKS);

            for (BakedQuad quad : src) {
                // 1) TFC 原 quad — 保留 tintindex → biome 着色草底
                out.add(quad);

                // 2) estfc 雪 quad — tintindex=-1 → 白色雪
                TextureAtlasSprite tfcSprite = quad.getSprite();
                ResourceLocation tfcName = tfcSprite.contents().name();

                if (tfcName.getNamespace().equals("tfc")) {
                    ResourceLocation estfcName = ResourceLocation.fromNamespaceAndPath(
                        "estfc", tfcName.getPath());
                    TextureAtlasSprite estfcSprite = atlas.getSprite(estfcName);

                    // ES 的 BakedQuadRetexturedAndReUV 强制 getTintIndex()=-1, isTinted()=false
                    BakedQuad snowQuad = new BakedQuadRetexturedAndReUV(quad, estfcSprite, false);
                    out.add(snowQuad);
                }
            }
            return out;
        }

        // 全部委托给 TFC 模型
        @Override public boolean useAmbientOcclusion() { return tfcModel.useAmbientOcclusion(); }
        @Override public boolean isGui3d() { return tfcModel.isGui3d(); }
        @Override public boolean usesBlockLight() { return tfcModel.usesBlockLight(); }
        @Override public boolean isCustomRenderer() { return tfcModel.isCustomRenderer(); }
        @Override public TextureAtlasSprite getParticleIcon() { return tfcModel.getParticleIcon(); }
        @Override public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() { return tfcModel.getOverrides(); }
        @Override public net.minecraft.client.renderer.block.model.ItemTransforms getTransforms() { return tfcModel.getTransforms(); }
    }
}
