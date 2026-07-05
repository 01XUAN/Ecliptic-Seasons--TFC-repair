package me.xuan.estfc.client;

import com.teamtea.eclipticseasons.client.model.IESReplaceModel;
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
 * <p>
 * 实现 {@link IESReplaceModel} → {@code isReplace() = true} → ES 用本模型
 * <b>替换</b>原方块模型，而非叠加渲染，消除双层贴图问题。
 * <p>
 * 简单雪模式（{@code simpleSnow=true}）：所有 quad 统一替换为 {@code estfc:block/snow}，
 * 适用于地面装饰物等薄片方块，无需逐贴图生成 snowy 版本。
 */
public class SnowTextureSwapModel implements BakedModel, IESReplaceModel {
    private static final ResourceLocation SIMPLE_SNOW = ResourceLocation.fromNamespaceAndPath("estfc", "block/snow");

    private final BakedModel tfcModel;
    private final boolean simpleSnow;

    public SnowTextureSwapModel(BakedModel tfcModel) {
        this(tfcModel, false);
    }

    public SnowTextureSwapModel(BakedModel tfcModel, boolean simpleSnow) {
        this.tfcModel = tfcModel;
        this.simpleSnow = simpleSnow;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        List<BakedQuad> src = tfcModel.getQuads(state, side, rand);
        List<BakedQuad> out = new ArrayList<>(src.size());

        TextureAtlas atlas = Minecraft.getInstance()
            .getModelManager()
            .getAtlas(TextureAtlas.LOCATION_BLOCKS);

        if (simpleSnow) {
            // 简单雪模式：所有 quad 统一用 snow.png，适合地面装饰物
            TextureAtlasSprite snowSprite = atlas.getSprite(SIMPLE_SNOW);
            if (isMissingSprite(snowSprite)) return src; // fallback
            for (BakedQuad quad : src) {
                out.add(new BakedQuadRetexturedAndReUV(quad, snowSprite, false));
            }
            return out;
        }

        for (BakedQuad quad : src) {
            TextureAtlasSprite tfcSprite = quad.getSprite();
            ResourceLocation tfcName = tfcSprite.contents().name();

            if (tfcName.getNamespace().equals("tfc")) {
                ResourceLocation estfcName = ResourceLocation.fromNamespaceAndPath(
                    "estfc", tfcName.getPath());
                TextureAtlasSprite estfcSprite = atlas.getSprite(estfcName);

                // estfc 贴图不在 atlas → 保留 TFC 原贴图（不生成悬浮雪层）
                if (isMissingSprite(estfcSprite)) {
                    out.add(quad);
                } else {
                    out.add(new BakedQuadRetexturedAndReUV(quad, estfcSprite, false));
                }
            } else {
                out.add(quad);
            }
        }
        return out;
    }

    /**
     * 检查 sprite 是否为 missing texture（贴图未注册到 atlas）。
     * 当 estfc snowy 贴图文件存在但未被 block model 引用时，atlas 返回 missingno。
     */
    private static boolean isMissingSprite(TextureAtlasSprite sprite) {
        return sprite.contents().name().getPath().equals("missingno");
    }

    // IESReplaceModel — 标记本模型应替换原方块模型，而非叠加
    @Override public boolean isReplace() { return true; }

    @Override public boolean useAmbientOcclusion() { return tfcModel.useAmbientOcclusion(); }
    @Override public boolean isGui3d() { return tfcModel.isGui3d(); }
    @Override public boolean usesBlockLight() { return tfcModel.usesBlockLight(); }
    @Override public boolean isCustomRenderer() { return tfcModel.isCustomRenderer(); }
    @Override public TextureAtlasSprite getParticleIcon() { return tfcModel.getParticleIcon(); }
    @Override public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() { return tfcModel.getOverrides(); }
    @Override public net.minecraft.client.renderer.block.model.ItemTransforms getTransforms() { return tfcModel.getTransforms(); }
}
