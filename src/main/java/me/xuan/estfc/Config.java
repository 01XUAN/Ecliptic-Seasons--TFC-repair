package me.xuan.estfc;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * TFC 兼容配置。
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_TFC_COMPAT = BUILDER
            .comment("启用 TFC 兼容模式。当启用时，节气和天气从 TFC 日历和气候系统派生。",
                     "Enable TFC compatibility mode. When enabled, solar terms and weather are derived from TFC's calendar and climate system.")
            .define("enableTFCCompat", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean enableTFCCompat() {
        return ENABLE_TFC_COMPAT.getAsBoolean();
    }
}
