package me.xuan.estfc.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * 仅替换贴图精灵的 BakedQuad，保留原始 UV 映射。
 * 与 ES 的 BakedQuadRetexturedAndReUV 的关键区别：
 * BakedQuadRetexturedAndReUV.remapQuad() 从顶点位置重新投影 UV，
 * 会破坏植物等有非标准 UV 的方块（如 sword_fern 的 "uv": [16,12,0,16]）。
 * <p>
 * 本类将旧精灵的 atlas UV 线性映射到新精灵的 atlas UV，
 * 保留 sprite 内的相对位置，仅替换纹理。
 * </p>
 * <p><b>tintIndex 处理：</b></p>
 * <ul>
 *   <li>{@code preserveTint=true}（ConnectedGrassBlock 路径）：
 *       保留原始 tintIndex。TFC 的 grassBlockColor 对 SNOWY=true
 *       返回白色(-1)，所有 quad 都会被染白——包括需要白色覆盖的
 *       grass_side overlay (tintIndex=1)。</li>
 *   <li>{@code preserveTint=false}（通用路径，默认）：
 *       tintIndex 强制 -1，雪贴图永不着色。</li>
 * </ul>
 */
public class BakedQuadTextureOnly extends BakedQuad {

    private static final int UV_OFFSET = computeUvOffset();
    private static final int VERTEX_STRIDE = DefaultVertexFormat.BLOCK.getVertexSize() / 4;

    /** UV0 元素在 BLOCK 顶点格式中的整数偏移（1.20.1 无 getOffset(VertexFormatElement)）。 */
    private static int computeUvOffset() {
        int offset = 0;
        for (VertexFormatElement element : DefaultVertexFormat.BLOCK.getElements()) {
            if (element.getUsage() == VertexFormatElement.Usage.UV && element.getIndex() == 0) {
                return offset;
            }
            offset += element.getByteSize() / 4;
        }
        return 4;
    }

    private final TextureAtlasSprite texture;
    private final int tintIndex;
    private final boolean tinted;

    /** 默认构造函数 — 强制 tintIndex=-1（无着色） */
    public BakedQuadTextureOnly(BakedQuad quad, TextureAtlasSprite textureIn) {
        this(quad, textureIn, false);
    }

    /**
     * @param quad         原始 BakedQuad
     * @param textureIn    替换用的贴图精灵
     * @param preserveTint true=保留原始 tintIndex（ConnectedGrassBlock 路径），
     *                     false=强制 -1（通用路径）
     */
    public BakedQuadTextureOnly(BakedQuad quad, TextureAtlasSprite textureIn, boolean preserveTint) {
        super(Arrays.copyOf(quad.getVertices(), quad.getVertices().length),
              preserveTint ? quad.getTintIndex() : -1,
              quad.getDirection(),
              textureIn,
              quad.isShade(),
              quad.hasAmbientOcclusion());
        this.texture = textureIn;
        this.tintIndex = preserveTint ? quad.getTintIndex() : -1;
        this.tinted = preserveTint && quad.isTinted();
        remapUV(quad.getSprite(), textureIn);
    }

    /**
     * Atlas UV → sprite 局部 UV [0,1] → 新精灵 atlas UV。
     * 等比例线性映射，不破坏原始 UV 方向。
     */
    private void remapUV(TextureAtlasSprite oldSprite, TextureAtlasSprite newSprite) {
        final float oU0 = oldSprite.getU0(), oU1 = oldSprite.getU1();
        final float oV0 = oldSprite.getV0(), oV1 = oldSprite.getV1();
        final float oUW = oU1 - oU0, oVH = oV1 - oV0;
        final float nU0 = newSprite.getU0(), nV0 = newSprite.getV0();
        final float nUW = newSprite.getU1() - nU0, nVH = newSprite.getV1() - nV0;

        for (int i = 0; i < 4; i++) {
            int j = i * VERTEX_STRIDE + UV_OFFSET;
            float localU = oUW > 0 ? (Float.intBitsToFloat(vertices[j]) - oU0) / oUW : 0f;
            float localV = oVH > 0 ? (Float.intBitsToFloat(vertices[j + 1]) - oV0) / oVH : 0f;
            vertices[j]     = Float.floatToRawIntBits(nU0 + localU * nUW);
            vertices[j + 1] = Float.floatToRawIntBits(nV0 + localV * nVH);
        }
    }

    @Override public @NotNull TextureAtlasSprite getSprite() { return texture; }
    @Override public int getTintIndex() { return tintIndex; }
    @Override public boolean isTinted() { return tinted; }
}
