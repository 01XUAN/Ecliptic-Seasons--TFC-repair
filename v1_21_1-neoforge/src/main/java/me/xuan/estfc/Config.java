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
    // 1. Snow Temperature — 覆雪温度阈值
    // ========================================================================

    public static final ModConfigSpec.DoubleValue SNOW_APPEARANCE_TEMP;
    public static final ModConfigSpec.DoubleValue SNOW_MELT_TEMP;
    public static final ModConfigSpec.DoubleValue SNOW_FULL_COVERAGE_TEMP;
    public static final ModConfigSpec.IntValue SNOW_RAINFALL_NO_SNOW;
    public static final ModConfigSpec.IntValue SNOW_RAINFALL_FULL_SNOW;
    public static final ModConfigSpec.IntValue DH_TEMP_PREFETCH_RADIUS;
    public static final ModConfigSpec.IntValue DH_TEMP_REQUESTS_PER_TICK;
    public static final ModConfigSpec.IntValue DH_TEMP_SERVER_THREADS;
    public static final ModConfigSpec.IntValue DH_TEMP_SERVER_SUBMIT_PER_TICK;

    static {
        BUILDER.push("Snow Temperature");
        BUILDER.comment(
            "控制 TFC 温度如何映射为覆雪深度 (0-100)。",
            "Controls how TFC temperature maps to snow depth (0-100)."
        );

        SNOW_APPEARANCE_TEMP = BUILDER
            .comment(
                "雪出现温度 (°C)：高于此温度 → 无雪。",
                "与完全覆雪温度之间的区间是过渡带：覆雪率随温度线性变化",
                "（默认设置下 -1°C→25%，-2°C→50%，-3°C→75%）。",
                "Snow appearance temperature (°C): above this → no snow.",
                "Between this and the full-coverage temperature the coverage ramps",
                "linearly (e.g. -1°C→25%, -2°C→50% with defaults).",
                "默认/Default: 0.0"
            )
            .defineInRange("snowAppearanceTemp", 0.0, -273.0, 500.0);

        SNOW_MELT_TEMP = BUILDER
            .comment(
                "融雪温度 (°C)：高于此温度积雪消融，低于此温度且有降水则积雪。",
                "建议与雪出现温度保持一致（默认均为 0°C）。",
                "Snow melt temperature (°C): above this snow melts, below this +",
                "precipitation it accumulates. Should match snowAppearanceTemp.",
                "默认/Default: 0.0"
            )
            .defineInRange("snowMeltTemp", 0.0, -273.0, 500.0);

        SNOW_FULL_COVERAGE_TEMP = BUILDER
            .comment(
                    "完全覆雪温度 (°C)：低于此温度 → 满雪 (100%)。",
                    "与雪出现温度的差值决定过渡带宽度（默认 0~-4°C）。",
                    "Full snow coverage temperature (°C): below this → 100% snow.",
                    "The gap to snowAppearanceTemp sets the transition band width.",
                    "默认/Default: -4.0"
            )
            .defineInRange("snowFullCoverageTemp", -4.0, -273.0, 500.0);

        SNOW_RAINFALL_NO_SNOW = BUILDER
            .comment(
                "无雪降水量阈值（mm/年，TFC 年均降水量）：低于此值 → 完全没有雪。",
                "Rainfall below this → no snow at all (mm/year).",
                "默认/Default: 80"
            )
            .defineInRange("snowRainfallNoSnow", 80, 0, 500);

        SNOW_RAINFALL_FULL_SNOW = BUILDER
            .comment(
                "满雪降水量阈值（mm/年）：高于此值 → 覆雪率不受降水影响。",
                "两阈值之间覆雪率线性过渡（如 115mm → 50%）。",
                "Rainfall above this → snow not reduced by rainfall; between the two",
                "thresholds coverage ramps linearly (e.g. 115mm → 50%).",
                "默认/Default: 150"
            )
            .defineInRange("snowRainfallFullSnow", 150, 0, 500);

        // [DH/voxy] 区域气候点（温度+表面高度）同步同时服务 DH 与 voxy 两条远景路径
        DH_TEMP_PREFETCH_RADIUS = BUILDER
            .comment(
                "[DH/voxy] 区域气候点（128 格网格）预取半径（格）。服务端按 128 格网格点",
                "确定性生成 avgSeaLevelTemp 与表面高度（纯种子函数、时不变）并同步客户端；",
                "此半径决定玩家周围预取的网格范围。远景覆雪温度 = avgSeaLevelTemp + 月温",
                "± 区域海拔。",
                "[DH/voxy] Prefetch radius of region climate points (128-block grid, blocks).",
                "Region points are generated server-side deterministically from the world seed",
                "and synced to the client; this radius bounds the prefetch area around the player.",
                "默认/Default: 1024"
            )
            .defineInRange("dhTempPrefetchRadius", 1024, 128, 16384);

        DH_TEMP_REQUESTS_PER_TICK = BUILDER
            .comment(
                "[DH/voxy] 客户端每 tick 发送的区域点请求上限。",
                "[DH/voxy] Max region-point requests sent per tick (client).",
                "默认/Default: 512"
            )
            .defineInRange("dhTempRequestsPerTick", 512, 1, 2048);

        DH_TEMP_SERVER_THREADS = BUILDER
            .comment(
                "[DH/voxy] 服务端区域气候点计算线程数（异步）。",
                "[DH/voxy] Server worker threads for region climate point computation (async).",
                "默认/Default: 4"
            )
            .defineInRange("dhTempServerThreads", 4, 1, 32);

        DH_TEMP_SERVER_SUBMIT_PER_TICK = BUILDER
            .comment(
                "[DH/voxy] 每 tick 提交给服务端计算线程池的区域点上限。",
                "[DH/voxy] Per-tick submit budget to the server computation pool.",
                "默认/Default: 64"
            )
            .defineInRange("dhTempServerSubmitPerTick", 64, 1, 512);

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
                "默认/Default: 10"
            )
            .defineInRange("tickChunkIntervalTicks", 10, 1, 200);

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
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SNOW_BLOCK_NAMESPACES;

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

        SNOW_BLOCK_NAMESPACES = BUILDER
            .comment(
                "参与覆雪的方块命名空间。上面的匹配规则只在这些命名空间内生效。",
                "tfc = 群峦本体；tfe = TerraFirmaCraft 内容向下移植模组（TerraFirmaEarth）。",
                "Block namespaces that participate in snow coverage.",
                "命名空间不存在时无副作用。",
                "默认/Default: [tfc, tfe]"
            )
            .defineListAllowEmpty(List.of("blockNamespaces"),
                () -> List.of("tfc", "tfe"),
                () -> "tfc",
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
    /** 无雪降水量阈值 (mm/年) */
    private static int cachedSnowRainfallNoSnow = 80;
    /** 满雪降水量阈值 (mm/年) */
    private static int cachedSnowRainfallFullSnow = 150;
    /** 区域温度预取半径（格） */
    private static int cachedDhTempPrefetchRadius = 1024;
    /** 每 tick 区域点请求上限 */
    private static int cachedDhTempRequestsPerTick = 512;
    /** 服务端计算线程数 */
    private static int cachedDhTempServerThreads = 4;
    /** 每 tick 提交给服务端线程池的上限 */
    private static int cachedDhTempServerSubmitPerTick = 64;
    /** tickChunk + biomeDepth 间隔（tick） */
    private static int cachedTickChunkIntervalTicks = 10;
    /** Biome 雪深同步间隔（tick） */
    private static int cachedBiomeSyncIntervalTicks = 100;
    /** 全局简单雪模式 */
    private static boolean cachedSnowTextureSimpleMode = false;
    /** 替换渲染模式 */
    private static boolean cachedSnowTextureReplaceMode = true;
    /** TFC 方块覆雪路径前缀缓存 */
    private static Set<String> cachedTfcBlockPrefixes = Collections.emptySet();
    /** TFC 方块简单雪列表缓存（完整 ID 或路径前缀） */
    private static Set<String> cachedTfcSimpleSnowBlocks = Collections.emptySet();
    /** TFC 连接纹理开关 */
    private static boolean cachedTfcConnectedTexture = false;
    /** TFC 树叶覆雪列表缓存 */
    private static Set<String> cachedTfcLeavesSnowBlocks = Collections.emptySet();
    /** 参与覆雪的方块命名空间缓存 */
    private static Set<String> cachedBlockNamespaces = Set.of("tfc", "tfe");

    // ========================================================================
    // Getters — 供其他类使用（返回缓存值，避免每帧查询 ModConfigSpec）
    // ========================================================================

    public static float snowAppearanceTemp() { return cachedSnowAppearanceTemp; }
    public static float snowFullCoverageTemp() { return cachedSnowFullCoverageTemp; }
    public static float snowMeltTemp() { return cachedSnowMeltTemp; }
    public static int snowRainfallNoSnow() { return cachedSnowRainfallNoSnow; }
    public static int snowRainfallFullSnow() { return cachedSnowRainfallFullSnow; }
    public static int dhTempPrefetchRadius() { return cachedDhTempPrefetchRadius; }
    public static int dhTempRequestsPerTick() { return cachedDhTempRequestsPerTick; }
    public static int dhTempServerThreads() { return cachedDhTempServerThreads; }
    public static int dhTempServerSubmitPerTick() { return cachedDhTempServerSubmitPerTick; }
    public static int tickChunkIntervalTicks() { return cachedTickChunkIntervalTicks; }
    public static int biomeSyncIntervalTicks() { return cachedBiomeSyncIntervalTicks; }
    public static boolean snowTextureSimpleMode() { return cachedSnowTextureSimpleMode; }
    public static boolean snowTextureReplaceMode() { return cachedSnowTextureReplaceMode; }
    public static Set<String> tfcBlockPrefixes() { return cachedTfcBlockPrefixes; }
    public static Set<String> tfcSimpleSnowBlocks() { return cachedTfcSimpleSnowBlocks; }
    public static boolean tfcConnectedTexture() { return cachedTfcConnectedTexture; }
    public static Set<String> tfcLeavesSnowBlocks() { return cachedTfcLeavesSnowBlocks; }
    public static Set<String> blockNamespaces() { return cachedBlockNamespaces; }

    // ========================================================================
    // UpdateConfig — 配置变更时刷新缓存
    // ========================================================================

    public static void updateConfig(ModConfigEvent event) {
        if (event instanceof ModConfigEvent.Unloading) return;
        if (event.getConfig().getSpec() != SPEC) return;

        cachedSnowAppearanceTemp = SNOW_APPEARANCE_TEMP.get().floatValue();
        cachedSnowFullCoverageTemp = SNOW_FULL_COVERAGE_TEMP.get().floatValue();
        cachedSnowMeltTemp = SNOW_MELT_TEMP.get().floatValue();
        cachedSnowRainfallNoSnow = SNOW_RAINFALL_NO_SNOW.get();
        cachedSnowRainfallFullSnow = SNOW_RAINFALL_FULL_SNOW.get();
        cachedDhTempPrefetchRadius = DH_TEMP_PREFETCH_RADIUS.get();
        cachedDhTempRequestsPerTick = DH_TEMP_REQUESTS_PER_TICK.get();
        cachedDhTempServerThreads = DH_TEMP_SERVER_THREADS.get();
        cachedDhTempServerSubmitPerTick = DH_TEMP_SERVER_SUBMIT_PER_TICK.get();
        cachedTickChunkIntervalTicks = TICK_CHUNK_INTERVAL_TICKS.get();
        cachedBiomeSyncIntervalTicks = BIOME_SYNC_INTERVAL_TICKS.get();
        cachedSnowTextureSimpleMode = SNOW_TEXTURE_SIMPLE_MODE.get();
        cachedSnowTextureReplaceMode = SNOW_TEXTURE_REPLACE_MODE.get();
        cachedTfcBlockPrefixes = new LinkedHashSet<>(SNOW_TFC_BLOCK_PREFIXES.get());
        cachedTfcSimpleSnowBlocks = new LinkedHashSet<>(SNOW_TFC_SIMPLE_SNOW_BLOCKS.get());
        cachedTfcConnectedTexture = TFC_CONNECTED_TEXTURE.get();
        cachedTfcLeavesSnowBlocks = new LinkedHashSet<>(SNOW_TFC_LEAVES_BLOCKS.get());
        cachedBlockNamespaces = new LinkedHashSet<>(SNOW_BLOCK_NAMESPACES.get());
    }
}
