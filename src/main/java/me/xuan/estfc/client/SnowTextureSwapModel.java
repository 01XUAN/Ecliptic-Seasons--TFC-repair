package me.xuan.estfc.client;

import com.teamtea.eclipticseasons.client.model.bakequad.BakedQuadRetexturedAndReUV;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 包装 TFC BakedModel，将其贴图精灵从 tfc: 替换为 estfc:。
 * BakedQuadRetexturedAndReUV 强制 tintindex=-1 → 雪永不着色。
 */
public class SnowTextureSwapModel implements BakedModel {
    private final BakedModel tfcModel;

    public SnowTextureSwapModel(BakedModel tfcModel) { this.tfcModel = tfcModel; }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        List<BakedQuad> src = tfcModel.getQuads(state, side, rand);
        List<BakedQuad> out = new ArrayList<>(src.size());

        TextureAtlas atlas = Minecraft.getInstance()
            .getModelManager()
            .getAtlas(TextureAtlas.LOCATION_BLOCKS);

        for (BakedQuad quad : src) {
            TextureAtlasSprite tfcSprite = quad.getSprite();
            ResourceLocation tfcName = tfcSprite.contents().name();

            if (tfcName.getNamespace().equals("tfc")) {
                ResourceLocation estfcName = ResourceLocation.fromNamespaceAndPath(
                    "estfc", tfcName.getPath());
                TextureAtlasSprite estfcSprite = atlas.getSprite(estfcName);
                out.add(new BakedQuadRetexturedAndReUV(quad, estfcSprite, false));
            } else {
                out.add(quad);
            }
        }
        return out;
    }

    @Override public boolean useAmbientOcclusion() { return tfcModel.useAmbientOcclusion(); }
    @Override public boolean isGui3d() { return tfcModel.isGui3d(); }
    @Override public boolean usesBlockLight() { return tfcModel.usesBlockLight(); }
    @Override public boolean isCustomRenderer() { return tfcModel.isCustomRenderer(); }
    @Override public TextureAtlasSprite getParticleIcon() { return tfcModel.getParticleIcon(); }
    @Override public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() { return tfcModel.getOverrides(); }
    @Override public net.minecraft.client.renderer.block.model.ItemTransforms getTransforms() { return tfcModel.getTransforms(); }
}
