package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.dries007.tfc.util.tracker.WeatherHelpers;
import net.dries007.tfc.util.tracker.WorldTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * 对外部模组（TFC / ES）状态的受保护读取。
 */
public final class ClimateQuery {

    private ClimateQuery() {}

    /** TFC 气候模型可用且 ES 天气启用时返回模型，否则 null。 */
    @Nullable
    public static ClimateModel getModelIfActive(Level level) {
        ClimateModel model = Climate.get(level);
        if (!model.supportsRain()) return null;
        if (!WorldTracker.get(level).isWeatherEnabled()) return null;
        return model;
    }

    // ==================== 降水检测 ====================

    /** 不可变载体 + 单 volatile 字段：两个值不可能被观察到撕裂 */
    private record PrecipSnapshot(long calendarTick, boolean precipitating) {}
    private static volatile PrecipSnapshot precipCache = new PrecipSnapshot(Long.MIN_VALUE, false);

    public static boolean isPrecipitating(Level level) {
        long calTicks = Calendars.get(level).getCalendarTicks();
        PrecipSnapshot snap = precipCache;                    // 单次 volatile 读
        if (snap.calendarTick() == calTicks) return snap.precipitating();
        ClimateModel model = Climate.get(level);
        boolean result = model.supportsRain() && model.getRain(calTicks) > 0;
        precipCache = new PrecipSnapshot(calTicks, result);   // 单次 volatile 写
        return result;
    }

    public static boolean isPrecipitating(ServerLevel level, BlockPos pos) {
        ClimateModel model = getModelIfActive(level);
        if (model == null) return false;
        return isPrecipitating(model, level, pos);
    }

    public static boolean isPrecipitating(ClimateModel model, ServerLevel level, BlockPos pos) {
        long calTicks = Calendars.get(level).getCalendarTicks();
        float rainIntensity = model.getRain(calTicks);
        float rainfall = model.getInstantRainfall(level, pos);
        return WeatherHelpers.isPrecipitating(rainIntensity, rainfall);
    }

    // ==================== 群系累积雪深 ====================

    /** ES 原生 O(1) 查询（bindId 直取），与 ES 自己的 getSnowDepthAtBiome 同源。 */
    public static int getBiomeDepthAt(Level level, BlockPos pos) {
        try {
            WeatherManager.BiomeWeather bw =
                WeatherManager.getBiomeWeather(level, level.getBiome(pos));
            return bw == null ? 0 : bw.getSnowDepth() & 0xFF;
        } catch (Exception e) {
            return 0;
        }
    }
}
