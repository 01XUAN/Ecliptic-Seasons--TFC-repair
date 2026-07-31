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

    public static int calcEffectiveDepth(float actualTemp, boolean precipitating, int biomeDepth) {
        return Math.min(100, Math.max(biomeDepth, tempToSnowDepth(actualTemp)));
    }

    public static boolean shouldSnow(int depth, long seed) {
        return depth > Math.abs(seed % 100);
    }
}
