package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.api.data.season.SnowDefinition;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowChecker;
import me.xuan.estfc.Config;
import me.xuan.estfc.util.BlockPatternMatcher;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * 为 datapack 未覆盖的 TFC 方块注册运行时雪定义（服务端 + 客户端）。
 *
 * <p>ES 的服务端/客户端分类都走 {@link SnowChecker#getUncacheSnow} → 它只读
 * {@link SnowChecker#SNOW_DEFINITION_MAP}（由 datapack 注册表装载）。过去果树树叶
 * ({@code tfc:plant/*_leaves}) 只在客户端渲染表注册、普通 TFC 植物因
 * {@code plant_manifest.json} 缺失根本没有雪定义 → 服务端分类为 FLAG_NONE → 永不覆雪。
 * 本类在 {@code SnowChecker.resetUpdate} 之后补齐这些缺口，让分类与渲染两侧都覆盖。</p>
 *
 * <p>匹配规则：已有定义的方块跳过（datapack 优先）；匹配树叶配置/前缀 → flag 1101
 * （ES 原生双层雪）；匹配 {@link Config#tfcBlockPrefixes()} → flag 1001
 * （模型由 {@code SnowModelMixin} 贴图替换提供）。</p>
 */
public final class SnowDefinitionRegistrar {

    private SnowDefinitionRegistrar() {}

    /** ES 自带双层雪模型：顶层雪 + 内部斑点 */
    private static final ResourceLocation LEAVES_TOP =
        ResourceLocation.fromNamespaceAndPath("eclipticseasons", "snowy_leaves_top");
    private static final ResourceLocation LEAVES_ATTACH =
        ResourceLocation.fromNamespaceAndPath("eclipticseasons", "snowy_leaves_attach");

    /** 普通 TFC 树树叶（datapack 若移除后也由此兜底） */
    private static final String WOOD_LEAVES_PREFIX = "wood/leaves/";

    public static void registerDefaults() {
        // 配置缓存未初始化（极早期）时无可匹配，直接返回
        if (Config.tfcBlockPrefixes().isEmpty() && Config.tfcLeavesSnowBlocks().isEmpty()) return;

        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (!"tfc".equals(id.getNamespace())) continue;

            // datapack 已有定义（groundcover / rock / wood/leaves / wild_crop / bush …）优先，不重复注册
            if (SnowChecker.SNOW_DEFINITION_MAP.containsKey(block)) continue;

            String path = id.getPath();
            String full = id.toString();

            SnowDefinition.Info info;
            if (isLeafBlock(path, full)) {
                info = SnowDefinition.Info.builder()
                    .flag(MapChecker.FLAG_CUSTOM_JSON_WITH_TOP_LEAVES)
                    .offset(0)
                    .mid(LEAVES_TOP)
                    .mid2(LEAVES_ATTACH)
                    .build();
            } else if (BlockPatternMatcher.matches(path, full, Config.tfcBlockPrefixes())) {
                info = SnowDefinition.Info.builder()
                    .flag(MapChecker.FLAG_CUSTOM_JSON_PLANTS)
                    .offset(1)
                    .build();
            } else {
                continue;
            }

            SnowDefinition.builder()
                .blocks(HolderSet.direct(BuiltInRegistries.BLOCK.wrapAsHolder(block)))
                .info(info)
                .build()
                .fillMap(SnowChecker.SNOW_DEFINITION_MAP);
        }
    }

    /** 树叶类：配置的树叶列表（默认 regex:plant/.*_leaves，含果树树叶）+ 普通树树叶前缀 */
    private static boolean isLeafBlock(String path, String full) {
        return path.startsWith(WOOD_LEAVES_PREFIX)
            || BlockPatternMatcher.matches(path, full, Config.tfcLeavesSnowBlocks());
    }
}
