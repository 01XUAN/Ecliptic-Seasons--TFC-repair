package me.xuan.estfc;

/**
 * estfc 模组配置。
 * 运行时可修改，用于调试 TFC 覆雪参数。
 */
public class EstfcConfig {
    /**
     * 未加载区块（DH/Voxy 远景）的温度偏移量（摄氏度）。
     * 正值 = 更保守（需要更冷才覆雪），负值 = 更激进（更容易覆雪）。
     * 默认 0°C：TFC 纬度+海拔公式已给出合理估值。
     * 如果 DH 远景在温暖地区仍有不应有的雪 → 设为正值（如 +3）。
     * 如果 DH 远景在寒冷地区雪太少 → 设为负值（如 -3）。
     * 注意：非零值会在加载/未加载区块边界产生可见的"等高线"。
     */
    public static float unloadedTempOffset = 0.0f;
}
