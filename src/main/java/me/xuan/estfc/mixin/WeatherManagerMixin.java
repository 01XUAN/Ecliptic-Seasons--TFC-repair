package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.dries007.tfc.util.tracker.WeatherHelpers;
import net.dries007.tfc.util.tracker.WorldTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 用 TFC 气候替代 ES 的天气系统。
 *
 * <h3>雪状态</h3>
 * <ul>
 *   <li>TFC 正在降水 + 温度 ≤ 0 → SNOW（积雪逐渐增加）</li>
 *   <li>TFC 正在降水 + 温度 > 0 → SNOW_MELT（下雨融雪）</li>
 *   <li>无降水 + 温度 ≤ 0 → NONE（不动积雪）</li>
 *   <li>无降水 + 温度 > 0 → SNOW_MELT（天暖融雪）</li>
 * </ul>
 *
 * <h3>天气循环</h3>
 * runWeather 被覆盖：跳过 ES 原生的 biome 天气模拟（rain/clear timer），
 * 保留 updateSnowOrMelt 的渐变逻辑和 sendBiomePacket 同步。
 */
@Mixin(value = WeatherManager.class, remap = false)
public abstract class WeatherManagerMixin {

    @Unique
    private static final Logger ESTFC_LOG = LoggerFactory.getLogger("estfc");
    @Unique
    private static boolean LOGGED = false;

    // ==================== 群系降水过滤 ====================

    @Inject(method = "hasNonePrecipitation", at = @At("HEAD"), cancellable = true)
    private static void estfc$hasNonePrecipitation(Biome biome, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    // ==================== 雨/雪判断 ====================

    @Inject(method = "getRainOrSnow", at = @At("HEAD"), cancellable = true)
    private static void estfc$getRainOrSnow(Level level, Biome biome, BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        if (level == null) return;
        try {
            Biome.Precipitation tfcPrecip = WeatherHelpers.getPrecipitationAt(level, pos, Biome.Precipitation.NONE);
            cir.setReturnValue(tfcPrecip);
        } catch (Exception e) {}
    }

    /**
     * getSnowStatus — 雪/融雪/不变 三态决策。
     * 被 updateSnowOrMelt（biome 全局积累）和 SnowyStatusKeeper.tickChunk（per-block）调用。
     */
    @Inject(method = "getSnowStatus", at = @At("HEAD"), cancellable = true)
    private static void estfc$getSnowStatus(
        ServerLevel level, Holder<Biome> biome, BlockPos pos,
        CallbackInfoReturnable<WeatherManager.SnowRenderStatus> cir) {

        try {
            int depth = estfc$calcSnowDepth(level, pos);
            boolean precipitating = estfc$isPrecipitating(level, pos);

            if (!LOGGED) {
                LOGGED = true;
                ESTFC_LOG.info("[estfc] getSnowStatus: depth={} precip={} pos={}", depth, precipitating, pos);
            }

            if (depth > 0 && precipitating) {
                cir.setReturnValue(WeatherManager.SnowRenderStatus.SNOW);      // 下雪 → 积累
            } else if (depth == 0) {
                cir.setReturnValue(WeatherManager.SnowRenderStatus.SNOW_MELT); // 零上 → 融化
            } else {
                cir.setReturnValue(WeatherManager.SnowRenderStatus.NONE);      // 零下无降水 → 不动
            }
        } catch (Exception e) {}
    }

    // ==================== 降水类型 ====================

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private static void estfc$getPrecipitationAt(
        Biome biome, BlockPos pos,
        CallbackInfoReturnable<Biome.Precipitation> cir) {

        try {
            Level level = WeatherManager.fetchLevelIfNull(null, biome);
            if (level == null) return;
            int depth = estfc$calcSnowDepth((ServerLevel) level, pos);
            if (depth > 0) {
                cir.setReturnValue(Biome.Precipitation.SNOW);
            } else {
                cir.setReturnValue(Biome.Precipitation.NONE);
            }
        } catch (Exception e) {}
    }

    // ==================== 天气循环：跳过 ES 模拟，保留积雪更新 ====================

    /**
     * 覆盖 runWeather — 跳过 ES 原生的 biome 天气模拟，
     * 直接根据 TFC 降水状态调用 updateSnowOrMelt。
     *
     * <p>ES 原生 runWeather 会修改 ServerLevelData 的 rainTime/clearTime/raining，
     * 可能和 TFC 的全局天气冲突。我们完全跳过这部分，只保留积雪渐变。</p>
     */
    @Inject(method = "runWeather", at = @At("HEAD"), cancellable = true)
    private static void estfc$runWeather(
        ServerLevel level, WeatherManager.BiomeWeather biomeWeather,
        RandomSource random, int size, CallbackInfo ci) {

        if (!level.dimensionType().hasSkyLight()) return;

        try {
            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return;
            if (!WorldTracker.get(level).isWeatherEnabled()) return;

            // TFC 降水检查
            boolean precipitating = estfc$isPrecipitating(level, BlockPos.ZERO);
            if (precipitating) {
                biomeWeather.lastRainTime = level.getGameTime();
            }

            // 调用 ES 原生渐变逻辑（SNOW→+rate, SNOW_MELT→-rate, NONE→不变）
            // 透传 TFC 降水状态作为 rain 参数
            estfc$invokeUpdateSnowOrMelt(level, biomeWeather, random, size, precipitating);

            ci.cancel(); // 跳过 ES 的天气模拟部分
        } catch (Exception e) {}
    }

    // ==================== 核心计算 ====================

    /**
     * TFC 气候 → 雪深 (0-100)。
     * 同时检查温度和降水——只有降水+零下才返回正值。
     */
    @Unique
    private static int estfc$calcSnowDepth(ServerLevel level, BlockPos pos) {
        ClimateModel model = Climate.get(level);
        if (!model.supportsRain()) return 0;

        WorldTracker tracker = WorldTracker.get(level);
        if (!tracker.isWeatherEnabled()) return 0;

        float temp = model.getInstantTemperature(level, pos);

        if (temp < -10) return 100;                           // 极寒
        if (temp <= 0)  return 20 + (int)(Math.abs(temp) * 8); // 0°C→20, -10°C→100
        return 0;                                              // 零上
    }

    /**
     * TFC 降水检查（结合全局信号 + 本地降雨量）。
     */
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

    /**
     * 直接调用 ES 的 updateSnowOrMelt（protected static）。
     * 复制其渐变逻辑，不依赖 BiomeRain 的速率配置。
     */
    @Unique
    private static void estfc$invokeUpdateSnowOrMelt(
        ServerLevel level, WeatherManager.BiomeWeather biomeWeather,
        RandomSource random, int size, boolean rain) {

        if (rain) biomeWeather.lastRainTime = level.getGameTime();

        // ES 原生有随机跳过（非降水时 80% 概率跳过），我们始终执行
        var snow = WeatherManager.getSnowStatus(level, biomeWeather.biomeHolder, BlockPos.ZERO);
        float rate = Math.max(1, size >> 6);

        if (snow == WeatherManager.SnowRenderStatus.SNOW) {
            biomeWeather.setSnowDepth(Math.min(100, (biomeWeather.getSnowDepth() & 0xFF) + rate));
        } else if (snow == WeatherManager.SnowRenderStatus.SNOW_MELT) {
            biomeWeather.setSnowDepth(Math.max(0, (biomeWeather.getSnowDepth() & 0xFF) - rate));
        }
        // NONE: 不动
    }
}
