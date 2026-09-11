package me.xuan.estfc.mixin;

import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 关掉 vanilla 的 {@code minecraft:freeze_top_layer} 地表特征。
 *
 * <p>它是「任何群系只要温度低就铺物理雪」的机制：按群系温度在生成时给地表放
 * {@code minecraft:snow} 雪层、给水面放 {@code minecraft:ice}。TFC 自己不碰这个特征
 * （全树搜不到 {@code FREEZE_TOP_LAYER}），所以 1.20.1 的冷群系新区块会带出物理雪/冰 ——
 * 运行期怎么拦都没用（TFC 的 tickChunk 那条路径早就被 estfc 关了）。</p>
 *
 * <p>注意这是 MC 类，不能像 TFC/ES 那样用 {@code remap = false}：方法名要走 refmap 重映射，
 * 所以本 mixin 用默认 {@code remap}，构建产物里的 {@code estfc.refmap.json} 必须含
 * {@code place → m_142674_} 这条映射（构建后核对过）。</p>
 */
@Mixin(SnowAndFreezeFeature.class)
public abstract class SnowAndFreezeFeatureMixin {

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void estfc$skipWorldgenSnowIce(FeaturePlaceContext<NoneFeatureConfiguration> context,
                                           CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
