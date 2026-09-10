package me.xuan.estfc.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * estfc 覆雪贴图查找。
 *
 * <p>estfc 只覆盖 TFC 的一部分方块（植物、作物、地面装饰物、散落岩石、树叶/树枝、浆果丛）。
 * 泥土/草/石头/木板这类方块没有对应雪贴图 —— 对它们必须<b>放弃</b>贴图替换，
 * 交回 ES 自带的白色覆盖层，否则会静默保留 TFC 原贴图、看起来完全没覆雪。</p>
 */
public final class SnowTextures {

    private SnowTextures() {}

    /** 查找 {@code tfc:<path>} 对应的 {@code estfc:<path>} 精灵；不存在返回 null。 */
    @Nullable
    public static TextureAtlasSprite find(String tfcPath) {
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);

        TextureAtlasSprite sprite = atlas.getSprite(new ResourceLocation("estfc", tfcPath));
        if (!isMissing(sprite)) return sprite;

        // dense_leaves → leaves 回退（落叶堆 / krummholz 用）
        if (tfcPath.contains("dense_leaves/")) {
            sprite = atlas.getSprite(new ResourceLocation("estfc",
                tfcPath.replace("leaves/dense_leaves/", "leaves/")));
            if (!isMissing(sprite)) return sprite;
        }
        return null;
    }

    /** 原版雪贴图（简单雪模式用；材质包可覆盖）。 */
    public static TextureAtlasSprite vanillaSnow() {
        return Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS)
            .getSprite(new ResourceLocation("minecraft", "block/snow"));
    }

    public static boolean isMissing(@Nullable TextureAtlasSprite sprite) {
        return sprite == null || sprite.contents().name().getPath().equals("missingno");
    }
}
