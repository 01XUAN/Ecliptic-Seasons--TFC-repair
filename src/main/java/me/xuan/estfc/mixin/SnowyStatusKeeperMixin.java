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
     * 替换 SNOW_MELT 分支中的 getSnowDepthAtBiome
     */
    @WrapOperation(
        method = "tickChunk",
        at = @At(value = "INVOKE",
            target = "Lcom/teamtea/eclipticseasons/common/core/biome/WeatherManager;getSnowDepthAtBiome(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/biome/Biome;)I",
            ordinal = 1)
    )
    private int estfc$replaceDepthForMelt(Level level, Biome biome, Operation<Integer> original,
                                           @Local BlockPos checkPos) {
        int depth = estfc$getPerBlockDepth(level, checkPos);
        return depth >= 0 ? depth : original.call(level, biome);
    }

    /**
     * 服务端已加载区块：由 tickWeatherCycle 缓慢累积的 biomeDepth 主导
     */
    @Unique
    private static int estfc$getPerBlockDepth(Level level, BlockPos pos) {
        try {
            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) return -1;
            float temp = model.getInstantTemperature(level, pos);
            float rainfall = model.getAverageRainfall(level, pos);
            int biomeDepth = ClimateQuery.getBiomeDepthAt(level, pos);
            return SnowDepthRules.depthFromTemp(temp, biomeDepth, rainfall);
        } catch (Exception e) {
            return -1;
        }
    }
}
