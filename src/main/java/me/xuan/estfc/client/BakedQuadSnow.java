package me.xuan.estfc.client;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.Arrays;

/**
 * 基于原始 UV 坐标重新映射贴图的 BakedQuad。
 * 与 {@code BakedQuadRetexturedAndReUV} 不同，此类不通过几何投影重算 UV，
 * 而是直接从原 quad 的 UV 坐标出发，经由原 sprite → [0,1] 归一化 → 新 sprite，
 * 保留原始 UV 布局，不会因 quad 超出方块边界而产生方向/拉伸错误。
 * <p>
 * 适用于 sword_fern、moss 等跨方块边界的植物模型。
 */
public class BakedQuadSnow extends BakedQuad {

    /**
     * UV0 在顶点数据中的 int 偏移。
     * DefaultVertexFormat 中 Position(3) + Color(1) = 4 ints，UV0 紧随其后。
     * 适用于 BLOCK (9 ints)、无 Normal (8 ints)、无 UV2 (7 ints) 等所有标准变体。
     */
    private static final int UV_OFFSET = 4;

    public BakedQuadSnow(BakedQuad quad, TextureAtlasSprite newSprite) {
        super(Arrays.copyOf(quad.getVertices(), quad.getVertices().length),
              -1, // 强制不着色
              quad.getDirection(),
              newSprite,
              quad.isShade(),
              quad.hasAmbientOcclusion());

        TextureAtlasSprite oldSprite = quad.getSprite();

        // 从实际数组长度推导每顶点 int 数，适配各种顶点格式
        // （BLOCK=9, 无 Normal=8, 无 UV2=7 等，Sodium 可能压缩顶点）
        int vertexSize = quad.getVertices().length / 4;

        float u0 = oldSprite.getU0();
        float v0 = oldSprite.getV0();
        float uSpan = oldSprite.getU1() - u0;
        float vSpan = oldSprite.getV1() - v0;

        if (uSpan <= 0) uSpan = 1f;
        if (vSpan <= 0) vSpan = 1f;

        for (int i = 0; i < 4; i++) {
            int offset = i * vertexSize;

            float u = Float.intBitsToFloat(this.vertices[offset + UV_OFFSET]);
            float v = Float.intBitsToFloat(this.vertices[offset + UV_OFFSET + 1]);

            // 原 sprite atlas UV → 归一化 [0,1]（不 clamp，允许超出方块边界的 UV）
            float normU = (u - u0) / uSpan;
            float normV = (v - v0) / vSpan;

            // 归一化 UV → 新 sprite atlas UV
            this.vertices[offset + UV_OFFSET]     = Float.floatToRawIntBits(newSprite.getU(normU));
            this.vertices[offset + UV_OFFSET + 1] = Float.floatToRawIntBits(newSprite.getV(normV));
        }
    }
}
