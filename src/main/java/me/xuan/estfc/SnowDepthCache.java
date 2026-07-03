package me.xuan.estfc;

/**
 * 客户端 biome 雪深缓存。
 * 从服务端 BiomeWeatherMessage 同步，供 DH 等客户端渲染使用。
 */
public class SnowDepthCache {
    /** biome ID → snow depth (0-100)，最大 1024 个 biome */
    public static final byte[] DEPTHS = new byte[1024];
}
