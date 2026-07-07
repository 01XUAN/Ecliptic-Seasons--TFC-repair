package me.xuan.estfc;

import com.teamtea.eclipticseasons.api.misc.IBiomeWeatherProvider;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.dries007.tfc.util.tracker.WeatherHelpers;
import net.dries007.tfc.util.tracker.WorldTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * TFC 驱动 ES 雪深的桥接方法。
 * 从 Mixin 类中分离出来，因为 Mixin 不允许非 private 的 static 方法。
 */
public class WeatherBridge {

    private static final Logger LOG = LoggerFactory.getLogger("estfc");
    private static boolean LOGGED = false;
    private static boolean FIRST_SYNC_DONE = false;

    /**
     * 每 tick 处理一个群系的雪深更新。
     * 由 {@code WeatherHelpersMixin} 在 TFC 的 {@code advanceWeatherCycle} TAIL 调用。
     *
     * <p>复用 ES 的 {@code IBiomeWeatherProvider} 群系轮转计数器，
     * 全部决策基于 TFC 气候数据。</p>
     */
    public static void tickWeatherCycle(ServerLevel level) {
        try {
            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return;

            WorldTracker tracker = WorldTracker.get(level);
            if (!tracker.isWeatherEnabled()) return;

            IBiomeWeatherProvider ip = level instanceof IBiomeWeatherProvider ips ? ips : null;
            if (ip == null) return;

            ArrayList<WeatherManager.BiomeWeather> biomes = ip.es$get();
            if (biomes == null || biomes.isEmpty()) return;

            int index = ip.es$getTickBiome();
            WeatherManager.BiomeWeather biomeWeather = biomes.get(index);
            if (biomeWeather == null || biomeWeather.biomeHolder == null) return;

            // 每个 biome 需要各自的海拔来获得不同的温度（而不是所有 biome 共享玩家位置的温度）
            // 策略：用玩家 X/Z（保证已加载），按 biome index 变化 Y 来模拟不同海拔
            // → TFC 海拔递减率 (-1.6°C/10m) 确保不同"海拔"的 biome 获得不同深度
            // 降水仍用玩家位置（降水不依赖海拔）
            BlockPos playerPos = level.players().isEmpty()
                ? BlockPos.ZERO.above(64)
                : level.players().getFirst().blockPosition();
            if (!level.isLoaded(playerPos)) return;

            // 不同 biome 分配不同采样海拔 (64~180)，提供温度多样性
            int sampleY = 64 + (index * 17) % 116;
            BlockPos samplePos = playerPos.atY(Math.min(sampleY, level.getMaxBuildHeight() - 1));
            int depth = calcSnowDepth(level, samplePos);
            boolean precip = isPrecipitating(level, playerPos);

            if (!LOGGED) {
                LOGGED = true;
                LOG.info("[estfc] tickWeatherCycle active: biome={} depth={} precip={} sampleY={}",
                    biomeWeather.biomeHolder.getKey().location(), depth, precip, sampleY);
            }

            // 更新群系级雪深 (rate = 1, 群系轮转提供自然的渐变速率)
            if (depth > 0 && precip) {
                // 零下 + 降水 → 积雪积累
                biomeWeather.setSnowDepth(Math.min(100, (biomeWeather.getSnowDepth() & 0xFF) + 1));
            } else if (depth == 0) {
                // 零上 → 融雪
                biomeWeather.setSnowDepth(Math.max(0, (biomeWeather.getSnowDepth() & 0xFF) - 1));
            }
            // 零下 + 无降水 → 不动（雪不会在零下消失！）

            // 首次运行时立即同步一次，避免 Voxy/DH 在世界加载初期读到空缓存
            if (!FIRST_SYNC_DONE && !level.players().isEmpty()) {
                WeatherManager.sendBiomePacket(level, biomes, level.players());
                FIRST_SYNC_DONE = true;
            }
            // 之后每 100 tick (~5 秒) 同步
            if (level.getGameTime() % 100 == 0 && !level.players().isEmpty()) {
                WeatherManager.sendBiomePacket(level, biomes, level.players());
            }
        } catch (Exception e) {}
    }

    /**
     * TFC 温度 → ES 雪深 (0-100)。所有地方统一的转换公式。
     * 阈值从 {@link Config} 读取（支持运行时修改）。
     * <ul>
     *   <li>temp > snowAppearanceTemp → 0（零上无雪）</li>
     *   <li>temp ∈ [snowFullCoverageTemp, snowAppearanceTemp] → 线性渐变</li>
     *   <li>temp < snowFullCoverageTemp → 100（满雪）</li>
     * </ul>
     */
    public static int tempToSnowDepth(float temp) {
        float appear = Config.snowAppearanceTemp();
        float full = Config.snowFullCoverageTemp();
        if (temp > appear) return 0;
        if (temp < full) return 100;
        // 线性插值: (appear - temp) / (appear - full) * 100
        return (int)((appear - temp) / (appear - full) * 100);
    }

    /**
     * TFC 瞬时温度 → ES 雪深 (0-100)。
     */
    public static int calcSnowDepth(ServerLevel level, BlockPos pos) {
        ClimateModel model = Climate.get(level);
        if (!model.supportsRain()) return 0;

        WorldTracker tracker = WorldTracker.get(level);
        if (!tracker.isWeatherEnabled()) return 0;

        float temp = model.getInstantTemperature(level, pos);
        return tempToSnowDepth(temp);
    }

    /**
     * 检查 TFC 是否正在降水。
     */
    public static boolean isPrecipitating(ServerLevel level, BlockPos pos) {
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
