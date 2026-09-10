package me.xuan.estfc.weather;

import me.xuan.estfc.Config;

/**
 * 温度 → 雪深的纯函数规则，不碰 Level。
 */
public final class SnowDepthRules {

    private SnowDepthRules() {}

    public static int tempToSnowDepth(float temp) {
        float appear = Config.snowAppearanceTemp();
        float full = Config.snowFullCoverageTemp();
        if (temp > appear) return 0;
        if (temp < full) return 100;
        return (int)((appear - temp) / (appear - full) * 100);
    }

    /**
     * 有效雪深（0-100）。
     *
     * <ul>
     *   <li>temp ≤ full（-4°C）→ 100：温度地板，满雪。</li>
     *   <li>过渡带（full..appear，-4..0°C）：有积雪时覆雪率 = min(累积量, 温度斜坡)
     *       —— 下过雪后回温，覆雪率跟随温度（-1°C→25%，-2°C→50%），累积量只作上限；
     *       干冷（无累积）时温度斜坡兜底（干冷地区也有覆雪）。</li>
     *   <li>temp > appear → 0。</li>
     * </ul>
     */
    /** 温度 → 有效雪深（零上恒 0，再乘降水系数）。 */
    public static int depthFromTemp(float actualTemp, int biomeDepth, float rainfall) {
        return actualTemp > Config.snowMeltTemp() ? 0 : calcEffectiveDepth(actualTemp, biomeDepth, rainfall);
    }

    /**
     * 有效雪深（0-100）：≤-4°C 满雪；过渡带内有积雪时取 min(累积量, 温度斜坡)
     * （下雪后回温覆雪率跟随温度：-1°C→25%，-2°C→50%），干冷时温度斜坡兜底。
     * 最后乘降水系数（< snowRainfallNoSnow → 0；> snowRainfallFullSnow → 1）。
     */
    public static int calcEffectiveDepth(float actualTemp, int biomeDepth, float rainfall) {
        int ramp = tempToSnowDepth(actualTemp);
        int depth = ramp == 100 ? 100 : (biomeDepth <= 0 ? ramp : Math.min(biomeDepth, ramp));
        return (int) (depth * rainfallFactor(rainfall));
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
