package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.api.data.season.SnowDefinition;
import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.client.util.ClientRef;
import me.xuan.estfc.Config;
import me.xuan.estfc.EstfcTags;
import me.xuan.estfc.client.SnowTextureSwapModel;
import net.dries007.tfc.common.blocks.soil.ConnectedGrassBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 覆雪模型处理。
 * <ul>
 * <li>ConnectedGrassBlock → 利用 TFC SNOWY 属性，直接用 TFC multipart 雪模型</li>
 * <li>树叶列表方块 → 通过 SnowDefinition 注册，ES 原生 flag 1101 处理</li>
 * <li>其他 TFC 方块 → SnowTextureSwapModel 替换贴图</li>
 * </ul>
 */
@Mixin(value = ExtraModelManager.class, remap = false)
public abstract class SnowModelMixin {

    private static final String REGEX_PREFIX = "regex:";
    private static final Map<String, Pattern> PATTERN_CACHE = new HashMap<>();

    // ── getSnowyModel hook ──

    @Inject(method = "getSnowyModel", at = @At("TAIL"), cancellable = true)
    private static void estfc$swapSnowModel(
        BlockState state, BlockState snowState, int flag, int offset,
        CallbackInfoReturnable<BakedModel> cir) {

        if (state == null) return;

        BlockModelShaper shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();

        if (state.getBlock() instanceof ConnectedGrassBlock) {
            if (Config.tfcConnectedTexture()) return;
            BlockState snowyState = state.setValue(ConnectedGrassBlock.SNOWY, true)
                .setValue(ConnectedGrassBlock.NORTH, false)
                .setValue(ConnectedGrassBlock.EAST, false)
                .setValue(ConnectedGrassBlock.SOUTH, false)
                .setValue(ConnectedGrassBlock.WEST, false);
            BakedModel snowyModel = shaper.getBlockModel(snowyState);
            if (snowyModel != null) {
                cir.setReturnValue(new SnowTextureSwapModel(snowyModel, false, snowyState));
            }
            return;
        }

        // 树叶列表中的方块 → 跳过，让 ES 原生 flag 1101 双层雪处理
        if (matchesBlockState(state, Config.tfcLeavesSnowBlocks())) return;

        if (!isTfcBlock(state)) return;

        BakedModel tfcModel = shaper.getBlockModel(state);
        if (tfcModel != null) {
            boolean useSimpleSnow = isSimpleSnowBlock(state);
            cir.setReturnValue(new SnowTextureSwapModel(tfcModel, useSimpleSnow));
        }
    }

    // ── clearForRebaked hook — 程序化注册树叶 SnowDefinition（无需 JSON） ──

    @Inject(method = "clearForRebaked", at = @At("RETURN"))
    private static void estfc$registerLeafSnowDefs(
        Map<ModelResourceLocation, BakedModel> modelRegistry, CallbackInfo ci) {

        Set<String> patterns = Config.tfcLeavesSnowBlocks();
        if (patterns.isEmpty()) return;

        ResourceLocation top =
            ResourceLocation.fromNamespaceAndPath("eclipticseasons", "snowy_leaves_top");
        ResourceLocation attach =
            ResourceLocation.fromNamespaceAndPath("eclipticseasons", "snowy_leaves_attach");

        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (!"tfc".equals(id.getNamespace())) continue;

            if (matchesBlockId(id.getPath(), id.toString(), patterns)) {
                registerLeafSnow(block, top, attach);
            }
        }
    }

    @Unique
    private static void registerLeafSnow(Block block, ResourceLocation top, ResourceLocation attach) {
        SnowDefinition.Info info = SnowDefinition.Info.builder()
            .flag(1101).offset(0).mid(top).mid2(attach).build();
        Holder<Block> holder = BuiltInRegistries.BLOCK.wrapAsHolder(block);
        HolderSet<Block> holderSet = HolderSet.direct(holder);
        SnowDefinition.builder().blocks(holderSet).info(info).build()
            .fillMap(ClientRef.snowClientDef);
    }

    // ── block classification ──

    @Unique
    private static boolean isSimpleSnowBlock(BlockState state) {
        if (Config.snowTextureSimpleMode()) return true;
        if (state.is(EstfcTags.SIMPLE_SNOW)) return true;
        return matchesBlockState(state, Config.tfcSimpleSnowBlocks());
    }

    @Unique
    private static boolean isTfcBlock(BlockState state) {
        if (state.is(EstfcTags.SNOWABLE)) return true;
        if (state.getBlockHolder().getKey().location() == null) return false;
        if (!"tfc".equals(state.getBlockHolder().getKey().location().getNamespace())) return false;
        if (state.getBlock() instanceof ConnectedGrassBlock) return false;
        return matchesBlockState(state, Config.tfcBlockPrefixes());
    }

    /**
     * 提取 block ID 并用 {@link #matchesEntry} 逐一匹配 patterns。
     */
    @Unique
    private static boolean matchesBlockState(BlockState state, Set<String> entries) {
        ResourceLocation id = state.getBlockHolder().getKey().location();
        if (id == null) return false;
        return matchesBlockId(id.getPath(), id.getNamespace() + ":" + id.getPath(), entries);
    }

    @Unique
    private static boolean matchesBlockId(String path, String fullId, Set<String> entries) {
        for (String entry : entries) {
            if (matchesEntry(path, fullId, entry)) return true;
        }
        return false;
    }

    /**
     * 统一的配置条目匹配。
     * <ul>
     *   <li>{@code regex:...} → 正则匹配路径</li>
     *   <li>以 / 结尾 → 路径前缀匹配</li>
     *   <li>含 : → 完整 ID 精确匹配</li>
     *   <li>其他 → 路径前缀匹配</li>
     * </ul>
     */
    @Unique
    private static boolean matchesEntry(String path, String fullId, String entry) {
        if (entry.startsWith(REGEX_PREFIX)) {
            try {
                Pattern p = PATTERN_CACHE.computeIfAbsent(entry,
                        k -> Pattern.compile(k.substring(REGEX_PREFIX.length())));
                return p.matcher(path).matches();
            } catch (Exception ignored) {
                return false;
            }
        }
        if (entry.endsWith("/")) {
            return path.startsWith(entry);
        }
        if (entry.contains(":")) {
            return fullId.equals(entry);
        }
        return path.startsWith(entry);
    }
}
