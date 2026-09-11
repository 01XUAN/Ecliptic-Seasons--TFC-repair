package me.xuan.estfc.mixin;

import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.world.chunkdata.ChunkData;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 关掉 TFC 在**世界生成**阶段铺物理雪/冰的入口。
 *
 * <p>{@code Climate.onChunkLoad} → {@code OverworldClimateModel.onChunkLoad} 就是那段
 * 「按 {@code snowPatchNoise + 温度} 给地表放 {@code minecraft:snow}、给水面放冰、
 * 并设草方块 SNOWY」的世界生成代码（TFC 自己注释写着"这段不该被使用"）。它的判据只跟
 * 温度和噪声有关，<b>与群系无关</b> —— 正好对应「任何群系只要温度低就出现物理雪」，
 * 而且它同时放雪和冰。TFC 在 {@code ServerLevel.tickChunk} 里并不调它，所以之前那些
 * 运行期拦截点全都碰不到它。</p>
 *
 * <p>选这里是收口点：{@code Climate.onChunkLoad} 是 TFC 自己的静态方法（不是 MC 覆写），
 * 方法名不会被 SRG 重映射，所以 {@code remap = false} 能稳定命中；而它的唯一调用方
 * {@code IceAndSnowFeature.place} 覆写的是 MC 的 {@code Feature.place}，在生产 jar 里叫
 * {@code m_142674_} —— 之前那个 {@code IceAndSnowFeatureMixin} 用字面量 {@code place} 找，
 * 从未生效（所以「日志里没有它打的标记」并不能说明特征没被放置）。</p>
 */
@Mixin(value = Climate.class, remap = false)
public abstract class ClimateMixin {

    @Inject(method = "onChunkLoad", at = @At("HEAD"), cancellable = true)
    private static void estfc$skipWorldgenSnow(WorldGenLevel level, ChunkAccess chunk, ChunkData chunkData,
                                               CallbackInfo ci) {
        ci.cancel();
    }
}
