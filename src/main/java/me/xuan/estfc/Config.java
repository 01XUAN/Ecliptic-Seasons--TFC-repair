package me.xuan.estfc;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * estfc 模组配置 — TFC 覆雪系统全部可调参数。
 *
 * <p>使用 NeoForge ModConfigSpec BUILDER 模式，与节气模组的配置系统兼容。
 * 配置项在 {@link EclipticSeasonsTFCrepair} 构造器中注册为 COMMON 类型，
 * 可通过 NeoForge 内置配置界面或 /config 命令修改。
 */
public class Config {
    // ========================================================================
    // Builder & Spec
    // ========================================================================

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ========================================================================
    // 1. General — 总开关
    // ========================================================================

    public static final ModConfigSpec.BooleanValue ENABLE_TFC_COMPAT;

    static {
        BUILDER.push("General");
        ENABLE_TFC_COMPAT = BUILDER
            .comment(
                "启用 TFC 兼容模式。",
                "当启用时，节气和天气从 TFC 日历和气候系统派生。",
                "Enable TFC compatibility mode. When enabled, solar terms and weather",
                "are derived from TFC's calendar and climate system."
            )
            .define("enableTFCCompat", true);
        BUILDER.pop();
    }

    // ========================================================================
    // 2. Snow Temperature — 覆雪温度阈值
    // ========================================================================

    public static final ModConfigSpec.DoubleValue SNOW_APPEARANCE_TEMP;
    public static final ModConfigSpec.DoubleValue SNOW_FULL_COVERAGE_TEMP;
    public static final ModConfigSpec.DoubleValue SNOW_MELT_TEMP;
    public static final ModConfigSpec.DoubleValue DH_UNLOADED_TEMP_OFFSET;

    static {
        BUILDER.push("Snow Temperature");
        BUILDER.comment(
            "控制 TFC 温度如何映射为覆雪深度 (0-100)。",
            "Controls how TFC temperature maps to snow depth (0-100)."
        );

        SNOW_APPEARANCE_TEMP = BUILDER
            .comment(
                "雪开始出现的温度 (°C)。高于此温度 → 无雪 (depth=0)。",
                "Temperature at which snow starts to appear (°C).",
                "Above this temperature → no snow (depth=0).",
                "默认/Default: 0.0"
            )
            .defineInRange("snowAppearanceTemp", 0.0, -20.0, 30.0);

        SNOW_FULL_COVERAGE_TEMP = BUILDER
            .comment(
                "100% 覆雪的温度 (°C)。低于此温度 → 满雪 (depth=100)。",
                "Temperature at which snow coverage reaches 100% (depth=100).",
                "Below this temperature → full snow.",
                "默认/Default: -4.0"
            )
            .defineInRange("snowFullCoverageTemp", -4.0, -50.0, 20.0);

        SNOW_MELT_TEMP = BUILDER
            .comment(
                "雪开始融化的温度 (°C)。高于此温度 → 融雪模式。",
                "应与 snowAppearanceTemp 保持一致（默认均为 0°C）。",
                "Temperature above which snow starts to melt (°C).",
                "Should match snowAppearanceTemp (default both 0°C).",
                "默认/Default: 0.0"
            )
            .defineInRange("snowMeltTemp", 0.0, -20.0, 30.0);

        DH_UNLOADED_TEMP_OFFSET = BUILDER
            .comment(
                "DH/Voxy 远景温度微调偏移 (°C)。",
                "主修正由 Z→avgSeaLevelTemp 缓存自动完成（从已加载区块采样插值），",
                "此偏移仅用于微调：正值=远景更暖（更少雪），负值=远景更冷（更多雪）。",
                "Temperature fine-tuning offset for DH/Voxy distant view (°C).",
                "Primary correction is automatic via Z→avgSeaLevelTemp cache",
                "(sampled and interpolated from loaded chunks).",
                "This offset is for fine-tuning: positive=warmer distant, negative=colder distant.",
                "默认/Default: 0.0"
            )
            .defineInRange("dhUnloadedTempOffset", 0.0, -30.0, 30.0);

        BUILDER.pop();
    }

    // ========================================================================
    // 3. Elevation & Climate — 海拔/气候计算参数
    // ========================================================================

    public static final ModConfigSpec.DoubleValue ELEVATION_LAPSE_RATE;
    public static final ModConfigSpec.DoubleValue SNOW_ACCUMULATION_SPEED;
    public static final ModConfigSpec.DoubleValue SNOW_MELT_SPEED;

    static {
        BUILDER.push("Climate Calculation");
        BUILDER.comment(
            "温度计算中的关键物理参数。",
            "Key physical parameters for temperature calculation."
        );

        ELEVATION_LAPSE_RATE = BUILDER
            .comment(
                "海拔递减率 (°C / 10 blocks)。TFC 标准值为 -1.6°C/10m。",
                "用于在未加载区块中用玩家温度为基准估算远方温度时的海拔修正。",
                "Elevation lapse rate (°C per 10 blocks). TFC standard is -1.6°C/10m.",
                "Used for elevation adjustment when estimating distant temperatures",
                "from the player's reference temperature.",
                "默认/Default: -1.6"
            )
            .defineInRange("elevationLapseRate", -1.6, -5.0, 0.0);

        SNOW_ACCUMULATION_SPEED = BUILDER
            .comment(
                "积雪累积速度倍率。影响 BiomeWeather 雪深每 tick 增长量。",
                "Snow accumulation speed multiplier. Affects biome snow depth growth per tick.",
                "默认/Default: 1.0"
            )
            .defineInRange("snowAccumulationSpeedMultiplier", 1.0, 0.0, 20.0);

        SNOW_MELT_SPEED = BUILDER
            .comment(
                "融雪速度倍率。影响 BiomeWeather 雪深每 tick 减少量。",
                "Snow melt speed multiplier. Affects biome snow depth reduction per tick.",
                "默认/Default: 1.0"
            )
            .defineInRange("snowMeltSpeedMultiplier", 1.0, 0.0, 20.0);

        BUILDER.pop();
    }

    // ========================================================================
    // 4. Snow Texture — 覆雪贴图
    // ========================================================================

    public static final ModConfigSpec.BooleanValue SNOW_TEXTURE_SIMPLE_MODE;
    public static final ModConfigSpec.BooleanValue SNOW_TEXTURE_REPLACE_MODE;
    public static final ModConfigSpec.ConfigValue<String> SNOW_TEXTURE_DEFAULT_PATH;

    static {
        BUILDER.push("Snow Texture");
        BUILDER.comment(
            "控制 TFC 方块覆雪贴图的渲染方式。",
            "Controls how snow textures are applied to TFC blocks."
        );

        SNOW_TEXTURE_SIMPLE_MODE = BUILDER
            .comment(
                "简单雪模式：对所有 TFC 方块统一使用 estfc:block/snow 贴图。",
                "Simple snow mode: use estfc:block/snow for all TFC blocks.",
                "默认/Default: false"
            )
            .define("snowTextureSimpleMode", false);

        SNOW_TEXTURE_REPLACE_MODE = BUILDER
            .comment(
                "替换渲染模式：ES 用本模型替换原方块模型（而非叠加渲染）。",
                "关闭后会使用 ES 的叠加模式（可能导致双层贴图）。",
                "Replacement mode: ES replaces the original block model with our snow model.",
                "Disabling uses ES's overlay mode (may cause double textures).",
                "默认/Default: true"
            )
            .define("snowTextureReplaceMode", true);

        SNOW_TEXTURE_DEFAULT_PATH = BUILDER
            .comment(
                "默认覆雪贴图路径（ResourceLocation 格式，不需要命名空间前缀）。",
                "Default snow texture path (ResourceLocation format, no namespace prefix).",
                "默认/Default: block/snow"
            )
            .define("snowTextureDefaultPath", "block/snow");

        BUILDER.pop();
    }

    // ========================================================================
    // 5. Debug — 调试选项
    // ========================================================================

    public static final ModConfigSpec.BooleanValue DEBUG_LOG_TEMPERATURE;
    public static final ModConfigSpec.BooleanValue DEBUG_LOG_SNOW_PLACEMENT;

    static {
        BUILDER.push("Debug");

        DEBUG_LOG_TEMPERATURE = BUILDER
            .comment(
                "在日志中输出每个位置的计算温度（前 50 条）。",
                "Log calculated temperature for each position (first 50 entries).",
                "默认/Default: false"
            )
            .define("debugLogTemperature", false);

        DEBUG_LOG_SNOW_PLACEMENT = BUILDER
            .comment(
                "在日志中输出雪块放置/移除事件。",
                "Log snow placement/removal events.",
                "默认/Default: false"
            )
            .define("debugLogSnowPlacement", false);

        BUILDER.pop();
    }

    // ========================================================================
    // Spec — 在最后构建
    // ========================================================================

    static final ModConfigSpec SPEC = BUILDER.build();

    // ========================================================================
    // Cached runtime values — 在 UpdateConfig 中刷新
    // ========================================================================

    /** 雪开始出现的温度 (°C)，高于此值为零雪 */
    private static float cachedSnowAppearanceTemp = 0.0f;
    /** 100% 覆雪的温度 (°C)，低于此值为满雪 */
    private static float cachedSnowFullCoverageTemp = -4.0f;
    /** 雪融化的温度 (°C) */
    private static float cachedSnowMeltTemp = 0.0f;
    /** 海拔递减率 (°C/10m) */
    private static float cachedElevationLapseRate = -1.6f;
    /** 远景温度偏移 */
    private static float cachedDhUnloadedTempOffset = 0.0f;
    /** TFC 兼容开关 */
    private static boolean cachedEnableTfcCompat = true;

    // ========================================================================
    // Getters — 供其他类使用（返回缓存值，避免每帧查询 ModConfigSpec）
    // ========================================================================

    public static boolean enableTFCCompat() { return cachedEnableTfcCompat; }
    public static float snowAppearanceTemp() { return cachedSnowAppearanceTemp; }
    public static float snowFullCoverageTemp() { return cachedSnowFullCoverageTemp; }
    public static float snowMeltTemp() { return cachedSnowMeltTemp; }
    public static float elevationLapseRate() { return cachedElevationLapseRate; }
    public static float dhUnloadedTempOffset() { return cachedDhUnloadedTempOffset; }

    // ========================================================================
    // UpdateConfig — 配置变更时刷新缓存
    // ========================================================================

    public static void UpdateConfig(ModConfigEvent event) {
        if (event instanceof ModConfigEvent.Unloading) return;
        if (event.getConfig().getSpec() != SPEC) return;

        cachedEnableTfcCompat = ENABLE_TFC_COMPAT.getAsBoolean();
        cachedSnowAppearanceTemp = SNOW_APPEARANCE_TEMP.get().floatValue();
        cachedSnowFullCoverageTemp = SNOW_FULL_COVERAGE_TEMP.get().floatValue();
        cachedSnowMeltTemp = SNOW_MELT_TEMP.get().floatValue();
        cachedElevationLapseRate = ELEVATION_LAPSE_RATE.get().floatValue();
        cachedDhUnloadedTempOffset = DH_UNLOADED_TEMP_OFFSET.get().floatValue();

        EclipticSeasonsTFCrepair.LOGGER.info(
            "[estfc] Config reloaded: enableTFC={}, snowRange=[{:.1f}, {:.1f}], melt={:.1f}, elevLapse={:.2f}, dhOffset={:.1f}",
            cachedEnableTfcCompat, cachedSnowFullCoverageTemp, cachedSnowAppearanceTemp,
            cachedSnowMeltTemp, cachedElevationLapseRate, cachedDhUnloadedTempOffset
        );
    }
}
