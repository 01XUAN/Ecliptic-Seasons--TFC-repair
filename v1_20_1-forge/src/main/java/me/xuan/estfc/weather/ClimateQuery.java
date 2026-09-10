package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.dries007.tfc.util.climate.OverworldClimateModel;
import net.dries007.tfc.util.tracker.WorldTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;

/**
 * 对外部模组（TFC / ES）状态的受保护读取。
 *
 * <p>1.20.1 的 TFC 气候 API 与 1.21.1 差异较大（无 {@code supportsRain()} / {@code getInstantTemperature} /
 * {@code getRain} / {@code isWeatherEnabled()}），版本差异全部收在本类，调用方只依赖这里的名字。</p>
 */
public final class ClimateQuery {

    private ClimateQuery() {}

    /** TFC 气候模型可用（Overworld 型，即支持天气）时返回模型，否则 null。 */
    @Nullable
    public static ClimateModel getModelIfActive(Level level) {
        return getOverworldModelIfActive(level);
    }

    /** 同上，但返回具体类型（月温/海拔复刻需要 @Invoker 到 OverworldClimateModel）。 */
    @Nullable
    public static OverworldClimateModel getOverworldModelIfActive(Level level) {
        ClimateModel model = Climate.model(level);
        return model instanceof OverworldClimateModel overworld ? overworld : null;
    }

    // 气候读取

    /** 当前位置的瞬时温度（°C）。 */
    public static float instantTemperature(Level level, BlockPos pos) {
        return Climate.getTemperature(level, pos);
    }

    /** 当前位置的年均降水量（mm/年）。 */
    public static float averageRainfall(Level level, BlockPos pos) {
        return Climate.getRainfall(level, pos);
    }

    // 降水检测

    /** 该位置此刻是否正在降水（雨或雪）。 */
    public static boolean isPrecipitating(Level level, BlockPos pos) {
        try {
            return WorldTracker.get(level).isRaining(level, pos);
        } catch (Exception e) {
            return false;
        }
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
