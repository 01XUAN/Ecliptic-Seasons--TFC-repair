package me.xuan.estfc;

import com.teamtea.eclipticseasons.api.misc.IBiomeWeatherProvider;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import me.xuan.estfc.Config;
import me.xuan.estfc.client.TfcUnloadedTempEstimator;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.dries007.tfc.util.tracker.WeatherHelpers;
import net.dries007.tfc.util.tracker.WorldTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class WeatherBridge {

    private static final long[] lastStepTick = new long[256];

    // ==================== 主循环 ====================

    public static void tickWeatherCycle(ServerLevel level) {
        try {
            ClimateModel model = getModelIfActive(level);
            if (model == null) return;

            IBiomeWeatherProvider ip = level instanceof IBiomeWeatherProvider ips ? ips : null;
            if (ip == null) return;

            ArrayList<WeatherManager.BiomeWeather> biomes = ip.es$get();
            if (biomes == null || biomes.isEmpty()) return;

            int index = ip.es$getTickBiome();
            WeatherManager.BiomeWeather biomeWeather = biomes.get(index);
            if (biomeWeather == null || biomeWeather.biomeHolder == null) return;

            var players = level.players();
            BlockPos playerPos = players.isEmpty()
                ? BlockPos.ZERO.above(level.getSeaLevel())
                : players.getFirst().blockPosition();
            if (!level.isLoaded(playerPos)) return;

            float playerTemp = model.getInstantTemperature(level, playerPos);
            boolean precip = isPrecipitating(model, level, playerPos);
            float meltTemp = Config.snowMeltTemp();
            long gameTime = level.getGameTime();
            int currentDepth = biomeWeather.getSnowDepth() & 0xFF;

            int interval = Config.tickChunkIntervalTicks();
            if (precip && playerTemp <= meltTemp && currentDepth < 100) {
                int gain = (int)((gameTime - lastStepTick[index]) / interval);
                if (gain > 0) {
                    lastStepTick[index] += gain * (long)interval;
                    biomeWeather.setSnowDepth(Math.min(100, currentDepth + gain));
                }
            } else if (playerTemp > meltTemp && currentDepth > 0) {
                int loss = (int)((gameTime - lastStepTick[index]) / interval);
                if (loss > 0) {
                    lastStepTick[index] += loss * (long)interval;
                    biomeWeather.setSnowDepth(Math.max(0, currentDepth - loss));
                }
            }

            int syncInterval = Config.biomeSyncIntervalTicks();
            if (gameTime % syncInterval == 0 && !level.players().isEmpty()) {
                WeatherManager.sendBiomePacket(level, biomes, level.players());
            }
        } catch (Exception ignored) {}
    }

    // ==================== 温度→雪深 ====================

    public static int tempToSnowDepth(float temp) {
        float appear = Config.snowAppearanceTemp();
        float full = Config.snowFullCoverageTemp();
        if (temp > appear) return 0;
        if (temp < full) return 100;
        return (int)((appear - temp) / (appear - full) * 100);
    }

    public static boolean shouldSnow(int depth, long seed) {
        return depth > Math.abs(seed % 100);
    }

    public static int calcEffectiveDepth(float actualTemp, boolean precipitating, int biomeDepth) {
        return Math.min(100, Math.max(biomeDepth, tempToSnowDepth(actualTemp)));
    }

    // ==================== 降水检测 ====================

    private static long cachedPrecipCalTick = -1;
    private static boolean cachedPrecipResult;

    public static boolean isPrecipitating(Level level) {
        long calTicks = Calendars.get(level).getCalendarTicks();
        if (calTicks == cachedPrecipCalTick) return cachedPrecipResult;
        cachedPrecipCalTick = calTicks;
        ClimateModel model = Climate.get(level);
        cachedPrecipResult = model.supportsRain() && model.getRain(calTicks) > 0;
        return cachedPrecipResult;
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

    // ==================== 辅助方法 ====================

    @Nullable
    public static ClimateModel getModelIfActive(Level level) {
        ClimateModel model = Climate.get(level);
        if (!model.supportsRain()) return null;
        if (!WorldTracker.get(level).isWeatherEnabled()) return null;
        return model;
    }

    /** DH/Voxy 远景 */
    public static int calcSnowDepthClient(Level level, ClimateModel model, BlockPos pos,
                                           int biomeDepth) {
        float temp = TfcUnloadedTempEstimator.getCorrectedTemperature(level, model, pos);
        if (temp > Config.snowMeltTemp()) return 0;
        return calcEffectiveDepth(temp, isPrecipitating(level), biomeDepth);
    }

    private static volatile ArrayList<?> cachedBiomeList;
    private static final ConcurrentHashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>, Integer> depthCache = new ConcurrentHashMap<>();

    public static int getBiomeDepthAt(Level level, BlockPos pos) {
        try {
            var biomes = WeatherManager.getBiomeList(level);
            if (biomes != cachedBiomeList) {
                cachedBiomeList = biomes;
                depthCache.clear();
            }
            var key = level.getBiome(pos).getKey();
            Integer d = depthCache.get(key);
            if (d != null) return d;
            // 缓存未命中 → 线性扫描并缓存
            if (biomes != null) {
                for (var bw : biomes) {
                    if (key != null && bw.biomeHolder != null && bw.biomeHolder.is(key)) {
                        int depth = bw.getSnowDepth() & 0xFF;
                        depthCache.put(key, depth);
                        return depth;
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
