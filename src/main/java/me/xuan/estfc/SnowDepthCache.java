package me.xuan.estfc;

/**
 * 客户端 biome 雪深缓存。
 * 从服务端 BiomeWeatherMessage 同步，按 ES biome ID 索引。
 */
public class SnowDepthCache {
    public static final byte[] DEPTHS = new byte[1024];
}
