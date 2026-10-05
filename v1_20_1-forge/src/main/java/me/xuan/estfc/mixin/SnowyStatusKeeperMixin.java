package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.teamtea.eclipticseasons.common.core.snow.WeatherStatusKeeper;
import me.xuan.estfc.Config;
import me.xuan.estfc.weather.ClimateQuery;
import me.xuan.estfc.weather.SnowDepthRules;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SnowyStatusKeeper.tickChunk 的注入：节流 + 深度替换。
 *
 * <p>驱动本方法的调用点是 {@link me.xuan.estfc.weather.ChunkSnowTicker}（1.20.1 上 ES 自己的
 * 驱动点被 TFC 掐掉了，原因见该类的 Javadoc）。</p>
 */
@Mixin(value = SnowyStatusKeeper.class, remap = false)
public abstract class SnowyStatusKeeperMixin {

    /** 按 tickChunkIntervalTicks 节流，取消 ES 原生每 tick 调用 */
    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void estfc$throttle(ServerLevel level, LevelChunk chunk, ChunkPos chunkPos, BlockPos checkPos, ChunkInfoMap chunkInfoMap, WeatherStatusKeeper weatherStatusKeeper, CallbackInfo ci) {
        if (level.getGameTime() % Config.tickChunkIntervalTicks() != 0) {
            ci.cancel();
        }
    }

    /**
     * 把 ES tickChunk 里两处 getSnowDepthAtBiome（biome 级累积深度）都换成 per-position 有效雪深。
     *
     * <p>原来只包了 {@code ordinal = 1}（SNOW_MELT 分支，源码里第二个调用），加雪分支
     * （{@code ordinal = 0}）仍读 biome 深度 —— 同一条判定在两个分支用两套深度：加雪看累积量
     * （无海拔、无降水量），融雪看 per-position 温度斜坡，互相拉锯；而远景（DH/voxy）走的是
     * {@code depthFromTemp}，于是过渡带（-4..0°C）里近景满雪、远景按斜坡打折，坡地上就是一条
     * 明显的偏差带。现在两个分支统一走 per-position 深度（与 ARCHITECTURE §2.2 的
     * 「所有路径统一」一致），顺带让加雪/融雪互为补集，不再来回翻。</p>
     */
    @WrapOperation(
        method = "tickChunk",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/biome/WeatherManager;getSnowDepthAtBiome(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/biome/Biome;)I")
    )
    private int estfc$replaceDepthForMelt(Level level, Biome biome, Operation<Integer> original,
                                           @Local BlockPos checkPos) {
        int depth = estfc$getPerBlockDepth(level, checkPos);
        return depth >= 0 ? depth : original.call(level, biome);
    }

    /**
     * 服务端已加载区块：按该点温度/降水算有效雪深，零上恒 0（即该点无条件融雪）
     */
    @Unique
    private static int estfc$getPerBlockDepth(Level level, BlockPos pos) {
        try {
            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) return -1;
            float temp = ClimateQuery.instantTemperature(level, pos);
            float rainfall = ClimateQuery.averageRainfall(level, pos);
            int biomeDepth = ClimateQuery.getBiomeDepthAt(level, pos);
            return SnowDepthRules.depthFromTemp(temp, biomeDepth, rainfall);
        } catch (Exception e) {
            return -1;
        }
    }
}
