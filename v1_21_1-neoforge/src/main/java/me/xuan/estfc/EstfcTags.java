package me.xuan.estfc;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * estfc 方块标签 — 供数据包/其他模组以标签方式添加覆雪方块。
 * <ul>
 *   <li>{@code estfc:simple_snow} — 所有 quad 统一替换为 snow.png</li>
 *   <li>{@code estfc:snowable} — 逐 quad 贴图替换（tfc: → estfc:）</li>
 * </ul>
 */
public class EstfcTags {
    public static final TagKey<Block> SIMPLE_SNOW = TagKey.create(
        Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("estfc", "simple_snow"));

    public static final TagKey<Block> SNOWABLE = TagKey.create(
        Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("estfc", "snowable"));
}
