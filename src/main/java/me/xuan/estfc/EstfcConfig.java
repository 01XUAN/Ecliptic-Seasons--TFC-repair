package me.xuan.estfc;

/**
 * estfc 模组配置 — 向后兼容委托。
 *
 * <p>所有配置现已迁移到 {@link Config}（NeoForge ModConfigSpec 体系）。
 * 本类保留以兼容现有代码引用，实际值委托给 Config 的缓存 getter。
 *
 * @deprecated 请使用 {@link Config} 中对应的配置方法。
 */
@Deprecated
public class EstfcConfig {
    /**
     * 未加载区块（DH/Voxy 远景）的温度偏移量（摄氏度）。
     * 委托给 {@link Config#dhUnloadedTempOffset()}。
     */
    public static float unloadedTempOffset = 0.0f;
}
