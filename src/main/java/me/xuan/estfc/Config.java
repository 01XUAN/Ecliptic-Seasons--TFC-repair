package me.xuan.estfc;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * estfc 模组配置 — TFC 覆雪系统全部可调参数。
 */
public class Config {
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
    public static final ModConfigSpec.DoubleValue SNOW_MELT_TEMP;
    public static final ModConfigSpec.DoubleValue SNOW_FULL_COVERAGE_TEMP;
    public static final ModConfigSpec.DoubleValue DH_UNLOADED_TEMP_OFFSET;
    public static final ModConfigSpec.IntValue UNLOADED_TEMP_SAMPLE_RANGE;

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

        SNOW_MELT_TEMP = BUILDER
            .comment(
                "雪开始融化的温度 (°C)。高于此温度 → 融雪模式。",
                "应与 snowAppearanceTemp 保持一致（默认均为 0°C）。",
                "Temperature above which snow starts to melt (°C).",
                "Should match snowAppearanceTemp (default both 0°C).",
                "默认/Default: 0.0"
            )
            .defineInRange("snowMeltTemp", 0.0, -20.0, 30.0);

        SNOW_FULL_COVERAGE_TEMP = BUILDER
            .comment(
                    "100% 覆雪的温度 (°C)。低于此温度 → 满雪 (depth=100)。",
                    "Temperature at which snow coverage reaches 100% (depth=100).",
                    "Below this temperature → full snow.",
                    "默认/Default: -4.0"
            )
            .defineInRange("snowFullCoverageTemp", -4.0, -50.0, 20.0);

        DH_UNLOADED_TEMP_OFFSET = BUILDER
            .comment(
                "DH/Voxy 远景温度微调偏移 (°C)。",
                "主修正：TfcUnloadedTempEstimator 从已加载区块沿 Z 轴采样 avgSeaLevelTemp，",
                "在采样范围内做线性插值/梯度外推，替换 ChunkData.EMPTY 的假 10°C 基线。",
                "海拔修正 0.16225°C/block + 南北半球月度温度由 TFC 内部自动处理。",
                "此偏移仅用于微调：正值=远景更暖（更少雪），负值=远景更冷（更多雪）。",
                "Temperature fine-tuning offset for DH/Voxy distant view (°C).",
                "Primary correction: TfcUnloadedTempEstimator samples avgSeaLevelTemp along Z",
                "from loaded chunks within the configured range, using linear interpolation or gradient",
                "extrapolation to replace ChunkData.EMPTY's fake 10°C baseline.",
                "Elevation lapse rate (0.16225°C/block) and N/S hemisphere monthly variation",
                "are handled automatically by TFC internals.",
                "This offset is for fine-tuning: positive=warmer distant, negative=colder distant.",
                "默认/Default: 0.0"
            )
            .defineInRange("dhUnloadedTempOffset", 0.0, -30.0, 30.0);

        UNLOADED_TEMP_SAMPLE_RANGE = BUILDER
            .comment(
                "TfcUnloadedTempEstimator 沿 Z 轴采样已加载区块温度的半径（格）。",
                "越大=远景温度越精确，越大=首次遍历越多但性能影响可忽略",
                "（已缓存位置直接跳过，实际查询数受已加载区块数量限制）。",
                "Sampling radius of TfcUnloadedTempEstimator along Z axis (blocks).",
                "Larger = more accurate distant temperature, negligible performance impact",
                "(cached positions are skipped, actual queries bounded by loaded chunks).",
                "默认/Default: 10240"
            )
            .defineInRange("unloadedTempSampleRange", 10240, 1024, 100000);

        BUILDER.pop();
    }

    // ========================================================================
    // 3. Elevation & Climate — 海拔/气候计算参数
    // ========================================================================

    public static final ModConfigSpec.IntValue TICK_CHUNK_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue BIOME_SYNC_INTERVAL_TICKS;

    static {
        BUILDER.push("Climate Calculation");
        BUILDER.comment("积雪/融雪速率由 tickChunkIntervalTicks 统一控制。");

        TICK_CHUNK_INTERVAL_TICKS = BUILDER
            .comment(
                "tickChunk + biomeDepth 处理间隔（tick）。同时控制积雪/融雪速度和性能。",
                "每 N tick：tickChunk处理 + biomeDepth ±1（100步=100×N tick）。",
                "Interval for both tickChunk and depth step. Controls speed and performance.",
                "默认/Default: 20"
            )
            .defineInRange("tickChunkIntervalTicks", 20, 1, 200);

        BIOME_SYNC_INTERVAL_TICKS = BUILDER
            .comment(
                "Biome 雪深同步到客户端的间隔（tick）。",
                "Interval in ticks for syncing biome snow depth to clients.",
                "默认/Default: 100 (5 秒)"
            )
            .defineInRange("biomeSyncIntervalTicks", 100, 20, 1200);

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
    // 5. TFC Connected Texture — TFC 连接纹理
    // ========================================================================

    public static final ModConfigSpec.BooleanValue TFC_CONNECTED_TEXTURE;

    static {
        BUILDER.push("TFC Connected Texture");
        BUILDER.comment(
            "启用 TFC 连接纹理。",
            "当启用时，ConnectedGrassBlock 的连接纹理正常显示，但这些方块不会覆雪。",
            "当禁用时，ConnectedGrassBlock 的连接被强制断开以正确显示覆雪模型。",
            "Enable TFC connected textures.",
            "When enabled, ConnectedGrassBlock connected textures display normally,",
            "but these blocks will not have snow.",
            "When disabled, ConnectedGrassBlock connections are forced off",
            "to correctly display the snow model."
        );
        TFC_CONNECTED_TEXTURE = BUILDER
            .comment(
                "默认/Default: false（禁用连接纹理以保证覆雪正常）"
            )
            .define("tfcConnectedTexture", false);
        BUILDER.pop();
    }

    // ========================================================================
    // 6. TFC Block Snow Prefixes — TFC 方块覆雪路径前缀
    // ========================================================================

    public static final ModConfigSpec.ConfigValue<List<? extends String>> SNOW_TFC_BLOCK_PREFIXES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SNOW_TFC_SIMPLE_SNOW_BLOCKS;

    static {
        BUILDER.push("TFC Block Snow Prefixes");
        BUILDER.comment(
            "控制哪些 TFC 方块路径前缀会被覆雪模型替换。",
            "Controls which TFC block path prefixes get snow model replacement."
        );

        SNOW_TFC_BLOCK_PREFIXES = BUILDER
            .comment(
                "TFC 方块匹配列表 — 这些方块的模型会被 SnowTextureSwapModel 替换。",
                "支持三种格式：",
                "  - 路径前缀：plant/（匹配所有以此开头的路径）",
                "  - 完整方块 ID：tfc:plant/plum_leaves",
                "  - 正则表达式：regex:plant/.*_leaves",
                "List of TFC block patterns for snow model replacement.",
                "Supports three formats:",
                "  - Path prefix (ends with /): plant/ (matches all paths starting with this)",
                "  - Full block ID: tfc:plant/plum_leaves",
                "  - Regex: regex:plant/.*_leaves"
            )
            .defineListAllowEmpty(List.of("tfcSnowBlockPrefixes"),
                () -> List.of("plant/", "crop/", "groundcover/", "wild_crop/",
                    "regex:wood\\/(?:fallen_leaves|twig)\\/\\w+",
                    "regex:rock\\/(?:mossy_)?loose\\/\\w+"
                    ),
                () -> "plant/",
                o -> true);

        SNOW_TFC_SIMPLE_SNOW_BLOCKS = BUILDER
            .comment(
                "TFC 方块列表 — 这些方块使用 simple snow 模式（所有面统一用 snow.png）。",
                "支持三种格式：路径前缀、完整 ID、正则表达式（同 tfcSnowBlockPrefixes）。",
                "List of TFC blocks that use simple snow mode (all quads → snow.png).",
                "Supports three formats: path prefix, full ID, regex (same as tfcSnowBlockPrefixes)."
            )
            .defineListAllowEmpty(List.of("tfcSimpleSnowBlocks"),
                () -> List.of(
                    // groundcover (不含 seaweed/driftwood/stick — 它们用非 simple 模式保留原始 UV)
                    "regex:groundcover\\/(?!seaweed|driftwood|stick)\\w+",
                    // loose rocks
                    "regex:rock\\/(?:mossy_)?loose\\/\\w+"
                ),
                () -> "tfc:groundcover/bone",
                o -> true);

        BUILDER.pop();
    }

    // ========================================================================
    // 7. TFC Leaves Snow — TFC 树叶覆雪
    // ========================================================================

    public static final ModConfigSpec.ConfigValue<List<? extends String>> SNOW_TFC_LEAVES_BLOCKS;

    static {
        BUILDER.push("TFC Leaves Snow");
        BUILDER.comment(
            "TFC 树叶方块覆雪列表。这些方块会通过 SnowTextureSwapModel 替换贴图。",
            "注意：TFC wood/leaves/* 树树叶已由 Ecliptic Seasons 原生系统处理（flag 1101 双层雪），",
            "不需要在此重复添加。此列表适用于未被 ES 覆盖的树叶类方块。",
            "TFC leaf blocks that get snow via SnowTextureSwapModel.",
            "Note: TFC wood/leaves/* tree leaves are already handled by ES natively",
            "(flag 1101 dual-layer snow) — do not duplicate here.",
            "This list is for leaf-like blocks not covered by ES snow definitions."
        );

        SNOW_TFC_LEAVES_BLOCKS = BUILDER
            .comment(
                "树叶覆雪方块列表，使用 ES 原生 flag 1101 双层雪（顶雪 + 内部斑点），",
                "而非 SnowTextureSwapModel。方块在列表中 → Mixin 跳过 → ES 原生处理。",
                "默认匹配 TFC 果树树叶（plum_leaves, cherry_leaves 等）。",
                "支持三种格式：路径前缀、完整 ID、正则表达式。",
                "Leaf blocks using ES native flag 1101 dual-layer snow",
                "(top snow + internal spots) instead of SnowTextureSwapModel.",
                "Blocks in this list → Mixin skips → ES handles natively.",
                "Default: TFC fruit tree leaves. Supports path prefix, full ID, regex."
            )
            .defineListAllowEmpty(List.of("tfcLeavesSnowBlocks"),
                () -> List.of("regex:plant/.*_leaves"),
                () -> "plant/plum_leaves",
                o -> true);

        BUILDER.pop();
    }

    // ========================================================================
    // Spec — 在最后构建
    // ========================================================================

    public static final ModConfigSpec SPEC = BUILDER.build();

    // ========================================================================
    // Cached runtime values — 在 UpdateConfig 中刷新
    // ========================================================================

    /** 雪开始出现的温度 (°C)，高于此值为零雪 */
    private static float cachedSnowAppearanceTemp = 0.0f;
    /** 100% 覆雪的温度 (°C)，低于此值为满雪 */
    private static float cachedSnowFullCoverageTemp = -4.0f;
    /** 雪融化的温度 (°C) */
    private static float cachedSnowMeltTemp = 0.0f;
    /** 远景温度偏移 */
    private static float cachedDhUnloadedTempOffset = 0.0f;
    /** 未加载区块温度采样半径 */
    private static int cachedUnloadedTempSampleRange = 10240;
    /** TFC 兼容开关 */
    private static boolean cachedEnableTfcCompat = true;
    /** tickChunk + biomeDepth 间隔（tick），默认 20 */
    private static int cachedTickChunkIntervalTicks = 20;
    /** Biome 雪深同步间隔（tick） */
    private static int cachedBiomeSyncIntervalTicks = 100;
    /** 全局简单雪模式 */
    private static boolean cachedSnowTextureSimpleMode = false;
    /** 替换渲染模式 */
    private static boolean cachedSnowTextureReplaceMode = true;
    /** 默认覆雪贴图路径 */
    private static String cachedSnowTextureDefaultPath = "block/snow";
    /** TFC 方块覆雪路径前缀缓存 */
    private static Set<String> cachedTfcBlockPrefixes = Collections.emptySet();
    /** TFC 方块简单雪列表缓存（完整 ID 或路径前缀） */
    private static Set<String> cachedTfcSimpleSnowBlocks = Collections.emptySet();
    /** TFC 连接纹理开关 */
    private static boolean cachedTfcConnectedTexture = false;
    /** TFC 树叶覆雪列表缓存 */
    private static Set<String> cachedTfcLeavesSnowBlocks = Collections.emptySet();

    // ========================================================================
    // Getters — 供其他类使用（返回缓存值，避免每帧查询 ModConfigSpec）
    // ========================================================================

    public static boolean enableTFCCompat() { return cachedEnableTfcCompat; }
    public static float snowAppearanceTemp() { return cachedSnowAppearanceTemp; }
    public static float snowFullCoverageTemp() { return cachedSnowFullCoverageTemp; }
    public static float snowMeltTemp() { return cachedSnowMeltTemp; }
    public static float dhUnloadedTempOffset() { return cachedDhUnloadedTempOffset; }
    public static int unloadedTempSampleRange() { return cachedUnloadedTempSampleRange; }
    public static int tickChunkIntervalTicks() { return cachedTickChunkIntervalTicks; }
    public static int biomeSyncIntervalTicks() { return cachedBiomeSyncIntervalTicks; }
    public static boolean snowTextureSimpleMode() { return cachedSnowTextureSimpleMode; }
    public static boolean snowTextureReplaceMode() { return cachedSnowTextureReplaceMode; }
    public static String snowTextureDefaultPath() { return cachedSnowTextureDefaultPath; }
    public static Set<String> tfcBlockPrefixes() { return cachedTfcBlockPrefixes; }
    public static Set<String> tfcSimpleSnowBlocks() { return cachedTfcSimpleSnowBlocks; }
    public static boolean tfcConnectedTexture() { return cachedTfcConnectedTexture; }
    public static Set<String> tfcLeavesSnowBlocks() { return cachedTfcLeavesSnowBlocks; }

    // ========================================================================
    // UpdateConfig — 配置变更时刷新缓存
    // ========================================================================

    public static void updateConfig(ModConfigEvent event) {
        if (event instanceof ModConfigEvent.Unloading) return;
        if (event.getConfig().getSpec() != SPEC) return;

        cachedEnableTfcCompat = ENABLE_TFC_COMPAT.getAsBoolean();
        cachedSnowAppearanceTemp = SNOW_APPEARANCE_TEMP.get().floatValue();
        cachedSnowFullCoverageTemp = SNOW_FULL_COVERAGE_TEMP.get().floatValue();
        cachedSnowMeltTemp = SNOW_MELT_TEMP.get().floatValue();
        cachedDhUnloadedTempOffset = DH_UNLOADED_TEMP_OFFSET.get().floatValue();
        cachedUnloadedTempSampleRange = UNLOADED_TEMP_SAMPLE_RANGE.get();
        cachedTickChunkIntervalTicks = TICK_CHUNK_INTERVAL_TICKS.get();
        cachedBiomeSyncIntervalTicks = BIOME_SYNC_INTERVAL_TICKS.get();
        cachedSnowTextureSimpleMode = SNOW_TEXTURE_SIMPLE_MODE.get();
        cachedSnowTextureReplaceMode = SNOW_TEXTURE_REPLACE_MODE.get();
        cachedSnowTextureDefaultPath = SNOW_TEXTURE_DEFAULT_PATH.get();
        cachedTfcBlockPrefixes = new LinkedHashSet<>(SNOW_TFC_BLOCK_PREFIXES.get());
        cachedTfcSimpleSnowBlocks = new LinkedHashSet<>(SNOW_TFC_SIMPLE_SNOW_BLOCKS.get());
        cachedTfcConnectedTexture = TFC_CONNECTED_TEXTURE.get();
        cachedTfcLeavesSnowBlocks = new LinkedHashSet<>(SNOW_TFC_LEAVES_BLOCKS.get());
    }
}
