package me.xuan.estfc.weather;

import me.xuan.estfc.Config;

/**
 * 温度/降水/累积覆雪率 → 覆雪率的纯函数规则，不碰 Level。
 */
public final class SnowDepthRules {

    private SnowDepthRules() {}

    public static int tempToSnowDepth(float temp) {
        float appear = Config.snowAppearanceTemp();
        float full = Config.snowFullCoverageTemp();
        if (appear <= full) return temp <= appear ? 100 : 0;
        if (temp > appear) return 0;
        if (temp < full) return 100;
        return (int)((appear - temp) / (appear - full) * 100);
    }

    /** 气候种子：只用于 BiomeSnowSeed 首次播种，不参与运行时远景/新区块渲染。 */
    public static int climateSeed(float temp, float rainfall) {
        return Math.round(tempToSnowDepth(temp) * rainfallFactor(rainfall));
    }

    /**
     * 当前温度下允许显示的覆雪率：高于融雪温度恒 0。
     *
     * <ul>
     *   <li>C≤1（未初始化/已融尽）：用气候种子，冷湿位置仍有雪，暖干位置为 0。</li>
     *   <li>C&gt;1：显示 C，但不超过降水上限和温度斜坡（0°C→0，-4°C→100）。
     *       温度斜坡是雪线附近 0..-4°C 的过渡带，不能让累积 C 把它压成整片 100%。</li>
     * </ul>
     */
    public static int depthFromCoverage(float actualTemp, int coverage, float rainfall) {
        if (actualTemp > Config.snowMeltTemp()) return 0;
        int ramp = tempToSnowDepth(actualTemp);
        if (coverage <= 1) {
            return Math.max(0, Math.min(ramp, climateSeed(actualTemp, rainfall)));
        }
        int maxCoverage = Math.round(maxCoverage(rainfall));
        return Math.max(0, Math.min(ramp, Math.min(coverage, maxCoverage)));
    }

    /** 降水允许的最大覆盖率（0-100）。 */
    public static float maxCoverage(float rainfall) {
        return 100f * rainfallFactor(rainfall);
    }

    /** 降水系数：< noSnow → 0；> fullSnow → 1；之间线性。 */
    public static float rainfallFactor(float rainfall) {
        float noSnow = Config.snowRainfallNoSnow();
        float fullSnow = Config.snowRainfallFullSnow();
        if (rainfall <= noSnow) return 0f;
        if (rainfall >= fullSnow || fullSnow <= noSnow) return 1f;
        return (rainfall - noSnow) / (fullSnow - noSnow);
    }

    public static boolean shouldSnow(int depth, long seed) {
        return depth > Math.abs(seed % 100);
    }
}
