package me.xuan.estfc;

/**
 * 客户端 biome 雪深缓存。
 * 从服务端 BiomeWeatherMessage 同步，供 DH/Voxy 等客户端渲染使用。
 *
 * <p>{@code INITIALIZED} 标记用于区分"缓存尚未从服务端同步"和"群系确实无雪(深度=0)"。
 * 避免 Voxy/DH 在首帧导入世界时因缓存全零而错误地判定无雪。</p>
 */
public class SnowDepthCache {
    /** biome ID → snow depth (0-100)，最大 1024 个 biome */
    public static final byte[] DEPTHS = new byte[1024];

    /** 首次收到 BiomeWeatherMessage 后置 true */
    public static boolean INITIALIZED = false;
}
