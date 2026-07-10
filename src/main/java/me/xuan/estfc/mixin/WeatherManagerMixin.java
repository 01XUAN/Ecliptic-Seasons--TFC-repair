package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import me.xuan.estfc.Config;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.dries007.tfc.util.tracker.WeatherHelpers;
import net.dries007.tfc.util.tracker.WorldTracker;
import net.dries007.tfc.world.chunkdata.ChunkData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WeatherManager.class, remap = false)
public abstract class WeatherManagerMixin {

    @Inject(method = "agentAdvanceWeatherCycle", at = @At("HEAD"), cancellable = true)
    private static void estfc$cancelAgentWeatherCycle(ServerLevel level, RandomSource random,
                                                       CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    @Inject(method = "hasNonePrecipitation", at = @At("HEAD"), cancellable = true)
    private static void estfc$hasNonePrecipitation(Biome biome, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    @Inject(method = "getRainOrSnow", at = @At("HEAD"), cancellable = true)
    private static void estfc$getRainOrSnow(Level level, Biome biome, BlockPos pos,
                                             CallbackInfoReturnable<Biome.Precipitation> cir) {
        if (level == null) return;
        try {
            Biome.Precipitation tfcPrecip = WeatherHelpers.getPrecipitationAt(level, pos, Biome.Precipitation.NONE);
            cir.setReturnValue(tfcPrecip);
        } catch (Exception e) {}
    }

    @Inject(
        method = "getSnowStatus(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;Z)Lcom/teamtea/eclipticseasons/common/core/biome/WeatherManager$SnowRenderStatus;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void estfc$getSnowStatus4(
        ServerLevel level, Holder<Biome> biome, BlockPos pos, boolean rain,
        CallbackInfoReturnable<WeatherManager.SnowRenderStatus> cir) {
        estfc$getSnowStatusImpl(level, pos, cir);
    }

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private static void estfc$getPrecipitationAt(
        Biome biome, BlockPos pos,
        CallbackInfoReturnable<Biome.Precipitation> cir) {

        try {
            Level level = WeatherManager.fetchLevelIfNull(null, biome);
            if (level == null) return;

            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return;
            if (!WorldTracker.get(level).isWeatherEnabled()) return;

            boolean precipitating = estfc$isPrecipitating((ServerLevel) level, pos);
            if (!precipitating) {
                cir.setReturnValue(Biome.Precipitation.NONE);
                return;
            }

            float temp = model.getInstantTemperature(level, pos);
            if (temp > Config.snowMeltTemp()) {
                cir.setReturnValue(Biome.Precipitation.RAIN);
            } else {
                cir.setReturnValue(Biome.Precipitation.SNOW);
            }
        } catch (Exception e) {}
    }

    @Unique
    private static void estfc$getSnowStatusImpl(
        ServerLevel level, BlockPos pos,
        CallbackInfoReturnable<WeatherManager.SnowRenderStatus> cir) {

        try {
            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return;
            if (!WorldTracker.get(level).isWeatherEnabled()) return;

            // ChunkData.EMPTY 时温度不可靠, 不改变现有雪状态
            ChunkData chunkData = ChunkData.get(level, pos);
            if (chunkData == ChunkData.EMPTY) {
                cir.setReturnValue(WeatherManager.SnowRenderStatus.NONE);
                return;
            }

            float temp = model.getInstantTemperature(level, pos);
            boolean precipitating = estfc$isPrecipitating(level, pos);
            float meltTemp = Config.snowMeltTemp();

            if (temp > meltTemp) {
                // 高于融雪温度 → 必定融雪
                cir.setReturnValue(WeatherManager.SnowRenderStatus.SNOW_MELT);
            } else if (precipitating) {
                // 低于融雪温度 + 正在降水 → 积雪
                cir.setReturnValue(WeatherManager.SnowRenderStatus.SNOW);
            } else {
                // 低于融雪温度 + 无降水 → 不动（雪保持）
                cir.setReturnValue(WeatherManager.SnowRenderStatus.NONE);
            }
        } catch (Exception e) {}
    }

    @Unique
    private static boolean estfc$isPrecipitating(ServerLevel level, BlockPos pos) {
        ClimateModel model = Climate.get(level);
        if (!model.supportsRain()) return false;

        WorldTracker tracker = WorldTracker.get(level);
        if (!tracker.isWeatherEnabled()) return false;

        long calTicks = Calendars.get(level).getCalendarTicks();
        float rainIntensity = model.getRain(calTicks);
        float rainfall = model.getInstantRainfall(level, pos);
        return WeatherHelpers.isPrecipitating(rainIntensity, rainfall);
    }
}
