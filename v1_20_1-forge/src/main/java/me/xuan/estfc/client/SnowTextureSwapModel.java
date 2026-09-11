package me.xuan.estfc.client;

import com.teamtea.eclipticseasons.client.model.IESReplaceModel;
import com.teamtea.eclipticseasons.client.model.bakequad.BakedQuadRetexturedAndReUV;
import me.xuan.estfc.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
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
    private static final ResourceLocation VANILLA_SNOW = new ResourceLocation("minecraft", "block/snow");

    /** 简单雪贴图 = 原版雪贴图（minecraft:block/snow），材质包可覆盖 */
    private static ResourceLocation simpleSnowTexture() {
        return VANILLA_SNOW;
    }

    private final BakedModel tfcModel;
    private final boolean simpleSnow;
    private final TextureAtlas atlas;
    /** 覆写渲染用的 BlockState（ConnectedGrassBlock 需强制 SNOWY=true） */
    @Nullable
    private final BlockState overrideState;

    public SnowTextureSwapModel(BakedModel tfcModel) {
        this(tfcModel, false, null);
    }

    public SnowTextureSwapModel(BakedModel tfcModel, boolean simpleSnow) {
        this(tfcModel, simpleSnow, null);
    }

    /**
     * @param tfcModel      TFC 原始 BakedModel
     * @param simpleSnow    简单雪模式（所有 quad → snow.png）
     * @param overrideState 覆写的 BlockState（非 null 时替代传入的 state），
     *                      用于 ConnectedGrassBlock 强制 SNOWY=true
     */
    public SnowTextureSwapModel(BakedModel tfcModel, boolean simpleSnow, @Nullable BlockState overrideState) {
        this.tfcModel = tfcModel;
        this.simpleSnow = simpleSnow;
        this.overrideState = overrideState;
        this.atlas = Minecraft.getInstance()
            .getModelManager()
            .getAtlas(TextureAtlas.LOCATION_BLOCKS);
    }

    private BlockState resolveState(@Nullable BlockState callerState) {
        return overrideState != null ? overrideState : callerState;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return swapQuads(tfcModel.getQuads(resolveState(state), side, rand));
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                     ModelData data, @Nullable RenderType renderType) {
        return swapQuads(tfcModel.getQuads(resolveState(state), side, rand, data, renderType));
    }

    /** 统一的贴图替换逻辑 */
    private List<BakedQuad> swapQuads(List<BakedQuad> src) {
        List<BakedQuad> out = new ArrayList<>(src.size());

        if (simpleSnow) {
            TextureAtlasSprite snowSprite = atlas.getSprite(simpleSnowTexture());
            if (isMissingSprite(snowSprite)) return src;
            for (BakedQuad quad : src) {
                out.add(new BakedQuadRetexturedAndReUV(quad, snowSprite, false));
            }
            return out;
        }

        // ConnectedGrassBlock 路径（overrideState != null）：保留原始 tintIndex
        // → TFC grassBlockColor 对 SNOWY=true 返回白色(-1)，覆盖层被染白
        // 通用路径（overrideState == null）：强制 tintIndex=-1，雪贴图不着色
        final boolean preserveTint = overrideState != null;

        for (BakedQuad quad : src) {
            TextureAtlasSprite sprite = quad.getSprite();
            ResourceLocation name = sprite.contents().name();
            String ns = name.getNamespace();

            if (ns.equals("tfc")) {
                TextureAtlasSprite estfcSprite = estfc$findSprite(atlas, name.getPath());
                if (estfcSprite != null) {
                    out.add(new BakedQuadTextureOnly(quad, estfcSprite, preserveTint));
                } else {
                    out.add(quad);
                }
            } else if (name.equals(VANILLA_SNOW)) {
                // minecraft:block/snow → estfc:block/snow
                TextureAtlasSprite estfcSnow = atlas.getSprite(simpleSnowTexture());
                out.add(isMissingSprite(estfcSnow) ? quad
                    : new BakedQuadTextureOnly(quad, estfcSnow, preserveTint));
            } else {
                out.add(quad);
            }
        }
        return out;
    }

    /**
     * 查找 estfc 覆雪贴图，带路径回退：
     * 1. estfc:<原路径>
     * 2. dense_leaves/ → leaves/ (krummholz/fallen_leaves 的 dense 贴图用普通树叶贴图)
     */
    @Nullable
    private static TextureAtlasSprite estfc$findSprite(TextureAtlas atlas, String tfcPath) {
        // 1. 精确匹配
        ResourceLocation rl = new ResourceLocation("estfc", tfcPath);
        TextureAtlasSprite sprite = atlas.getSprite(rl);
        if (!isMissingSprite(sprite)) return sprite;

        // 2. dense_leaves → leaves 回退（落叶堆/krummholz 树叶用）
        if (tfcPath.contains("dense_leaves/")) {
            String fallback = tfcPath.replace("leaves/dense_leaves/", "leaves/");
            rl = new ResourceLocation("estfc", fallback);
            sprite = atlas.getSprite(rl);
            if (!isMissingSprite(sprite)) return sprite;
        }

        return null;
    }

    /**
     * 检查 sprite 是否为 missing texture（贴图未注册到 atlas）。
     */
    private static boolean isMissingSprite(TextureAtlasSprite sprite) {
        return sprite.contents().name().getPath().equals("missingno");
    }

    // IESReplaceModel — 标记本模型应替换原方块模型，而非叠加
    @Override public boolean isReplace() { return Config.snowTextureReplaceMode(); }

    @Override public boolean useAmbientOcclusion() { return tfcModel.useAmbientOcclusion(); }
    @Override public boolean isGui3d() { return tfcModel.isGui3d(); }
    @Override public boolean usesBlockLight() { return tfcModel.usesBlockLight(); }
    @Override public boolean isCustomRenderer() { return tfcModel.isCustomRenderer(); }
    @Override public TextureAtlasSprite getParticleIcon() { return tfcModel.getParticleIcon(); }
    @Override public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() { return tfcModel.getOverrides(); }
    @Override public net.minecraft.client.renderer.block.model.ItemTransforms getTransforms() { return tfcModel.getTransforms(); }
}
