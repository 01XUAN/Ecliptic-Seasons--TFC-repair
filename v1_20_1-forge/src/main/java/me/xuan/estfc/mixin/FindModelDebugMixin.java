package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import me.xuan.estfc.client.DebugCounters;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/** 临时诊断：ES 判定「有雪」的次数。 */
@Mixin(value = ExtraModelManager.class, remap = false)
public abstract class FindModelDebugMixin {

    @Inject(method = "findModel", at = @At("RETURN"))
    private static void estfc$countFindModel(BlockAndTintGetter getter, BlockPos pos, BlockState state,
                                             RandomSource random, long seed,
                                             @Nullable BlockPos.MutableBlockPos checkPos,
                                             CallbackInfoReturnable<BakedModel> cir) {
        if (cir.getReturnValue() != null) DebugCounters.FIND_MODEL_HITS.incrementAndGet();
    }
}
