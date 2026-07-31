package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.api.misc.IBiomeWeatherProvider;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import me.xuan.estfc.Config;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;

/**
 * 群系雪深主循环：由 TFC advanceWeatherCycle 之后驱动，
 * 按 tick 间隔缓慢累积/消融 ES 群系雪深并定期同步客户端。
 */
public final class WeatherCycleService {

    private WeatherCycleService() {}

    private static final long[] lastStepTick = new long[256];

    public static void tickWeatherCycle(ServerLevel level) {
        try {
            ClimateModel model = ClimateQuery.getModelIfActive(level);
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
            boolean precip = ClimateQuery.isPrecipitating(model, level, playerPos);
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
}
