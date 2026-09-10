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
import net.minecraft.world.level.biome.Biome;

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

    // 降水检测

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

    // 群系累积雪深

    public static int getBiomeDepthAt(Level level, BlockPos pos) {
        try {
            return getBiomeDepth(level, level.getBiome(pos).value());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 已知群系时的雪深查询。
     *
     * <p>远景路径必须走这个重载：{@code level.getBiome(pos)} 对未加载区块返回占位群系，
     * 雪深恒为 0；voxy 从自己的 LOD 数据里解析出的群系才是该位置的真实群系。</p>
     */
    public static int getBiomeDepth(Level level, Biome biome) {
        try {
            WeatherManager.BiomeWeather bw = WeatherManager.getBiomeWeather(level, biome);
            return bw == null ? 0 : bw.getSnowDepth() & 0xFF;
        } catch (Exception e) {
            return 0;
        }
    }
}
