package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.api.data.season.SnowDefinition;
import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.client.util.ClientRef;
import me.xuan.estfc.Config;
import me.xuan.estfc.EstfcTags;
import me.xuan.estfc.client.SnowTextureSwapModel;
import me.xuan.estfc.util.BlockPatternMatcher;
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

import java.util.Map;
import java.util.Set;

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
        if (BlockPatternMatcher.matches(state, Config.tfcLeavesSnowBlocks())) return;

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
            new ResourceLocation("eclipticseasons", "snowy_leaves_top");
        ResourceLocation attach =
            new ResourceLocation("eclipticseasons", "snowy_leaves_attach");

        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (!Config.blockNamespaces().contains(id.getNamespace())) continue;

            if (BlockPatternMatcher.matches(id.getPath(), id.toString(), patterns)) {
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
        return BlockPatternMatcher.matches(state, Config.tfcSimpleSnowBlocks());
    }

    @Unique
    private static boolean isTfcBlock(BlockState state) {
        if (state.is(EstfcTags.SNOWABLE)) return true;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) return false;
        if (!Config.blockNamespaces().contains(id.getNamespace())) return false;
        if (state.getBlock() instanceof ConnectedGrassBlock) return false;
        return BlockPatternMatcher.matches(state, Config.tfcBlockPrefixes());
    }
}
