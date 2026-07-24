package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import me.xuan.estfc.Config;
import me.xuan.estfc.EstfcTags;
import me.xuan.estfc.client.SnowTextureSwapModel;
import net.dries007.tfc.common.blocks.soil.ConnectedGrassBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 覆雪模型处理。
 * <ul>
 * <li>ConnectedGrassBlock → 利用 TFC SNOWY 属性，直接用 TFC multipart 雪模型</li>
 * <li>其他 TFC 方块 → SnowTextureSwapModel 替换贴图</li>
 * </ul>
 *
 * <p>配置条目支持三种格式：
 * <ul>
 *   <li>{@code plant/} — 路径前缀（以 / 结尾），匹配所有以此开头的方块路径</li>
 *   <li>{@code tfc:plant/plum_leaves} — 完整方块 ID 精确匹配</li>
 *   <li>{@code regex:plant/.*_leaves} — 正则表达式（匹配方块完整路径）</li>
 * </ul>
 */
@Mixin(value = ExtraModelManager.class, remap = false)
public abstract class SnowModelMixin {

    private static final String REGEX_PREFIX = "regex:";

    @Inject(method = "getSnowyModel", at = @At("TAIL"), cancellable = true)
    private static void estfc$swapSnowModel(
        BlockState state, BlockState snowState, int flag, int offset,
        CallbackInfoReturnable<BakedModel> cir) {

        if (state == null) return;

        BlockModelShaper shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();

        if (state.getBlock() instanceof ConnectedGrassBlock) {
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

        if (!isTfcBlock(state)) return;

        BakedModel tfcModel = shaper.getBlockModel(state);
        if (tfcModel != null) {
            boolean useSimpleSnow = isSimpleSnowBlock(state);
            cir.setReturnValue(new SnowTextureSwapModel(tfcModel, useSimpleSnow));
        }
    }

    @Unique
    private static boolean isSimpleSnowBlock(BlockState state) {
        if (state.is(EstfcTags.SIMPLE_SNOW)) return true;

        ResourceLocation id = state.getBlockHolder().getKey().location();
        if (id == null) return false;
        String fullId = id.getNamespace() + ":" + id.getPath();
        String path = id.getPath();

        for (String entry : Config.tfcSimpleSnowBlocks()) {
            if (matchesEntry(path, fullId, entry)) return true;
        }
        return false;
    }

    @Unique
    private static boolean isTfcBlock(BlockState state) {
        if (state.is(EstfcTags.SNOWABLE)) return true;

        ResourceLocation id = state.getBlockHolder().getKey().location();
        if (id == null) return false;
        if (!"tfc".equals(id.getNamespace())) return false;
        if (state.getBlock() instanceof ConnectedGrassBlock) return false;

        String path = id.getPath();
        String fullId = "tfc:" + path;

        for (String entry : Config.tfcBlockPrefixes()) {
            if (matchesEntry(path, fullId, entry)) return true;
        }
        return false;
    }

    /**
     * 统一的配置条目匹配。
     * <ul>
     *   <li>{@code regex:...} → 正则匹配完整方块路径</li>
     *   <li>以 / 结尾 → 路径前缀匹配</li>
     *   <li>含 : → 完整 ID 精确匹配</li>
     *   <li>其他 → 路径前缀匹配</li>
     * </ul>
     */
    @Unique
    private static boolean matchesEntry(String path, String fullId, String entry) {
        if (entry.startsWith(REGEX_PREFIX)) {
            String regex = entry.substring(REGEX_PREFIX.length());
            try {
                return Pattern.matches(regex, path);
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
