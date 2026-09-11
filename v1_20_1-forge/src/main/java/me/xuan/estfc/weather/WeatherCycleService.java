package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.api.misc.IBiomeWeatherProvider;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import me.xuan.estfc.Config;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群系雪深主循环：由 TFC advanceWeatherCycle 之后驱动，
 * 按 tick 间隔缓慢累积/消融 ES 群系雪深并定期同步客户端。
 */
public final class WeatherCycleService {

    private static final Logger LOGGER = LoggerFactory.getLogger("estfc");

    private WeatherCycleService() {}

    /**
     * 各维度的群系步进时间戳。按维度分键（不同维度各有一张群系表，索引不通用），
     */
    private static final Map<ResourceKey<Level>, long[]> LAST_STEP_TICK = new ConcurrentHashMap<>();

    private static long[] stepTicks(ServerLevel level, int size) {
        return LAST_STEP_TICK.compute(level.dimension(), (k, arr) ->
            (arr == null || arr.length < size)
                ? Arrays.copyOf(arr == null ? new long[0] : arr, size)
                : arr);
    }

    public static void tickWeatherCycle(ServerLevel level) {
        try {
            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) return;

            IBiomeWeatherProvider ip = level instanceof IBiomeWeatherProvider ips ? ips : null;
            if (ip == null) return;

            ArrayList<WeatherManager.BiomeWeather> biomes = ip.es$get();
            if (biomes == null || biomes.isEmpty()) return;

            long[] lastStepTick = stepTicks(level, biomes.size());
            int index = ip.es$getTickBiome();
            WeatherManager.BiomeWeather biomeWeather = biomes.get(index);
            if (biomeWeather == null || biomeWeather.biomeHolder == null) return;

            var players = level.players();
            BlockPos playerPos = players.isEmpty()
                ? BlockPos.ZERO.above(level.getSeaLevel())
                : players.get(0).blockPosition();
            if (!level.isLoaded(playerPos)) return;

            float playerTemp = ClimateQuery.instantTemperature(level, playerPos);
            boolean precip = ClimateQuery.isPrecipitating(level, playerPos);
            float meltTemp = Config.snowMeltTemp();
            long gameTime = level.getGameTime();
            int currentDepth = biomeWeather.getSnowDepth() & 0xFF;

            int interval = Config.tickChunkIntervalTicks();
            if (precip && playerTemp <= meltTemp && currentDepth < 100) {
                int gain = (int)((gameTime - lastStepTick[index]) / interval);
                if (gain > 0) {
                    biomeWeather.setSnowDepth(Math.min(100, currentDepth + gain));
                }
            } else if (playerTemp > meltTemp && currentDepth > 0) {
                int loss = (int)((gameTime - lastStepTick[index]) / interval);
                if (loss > 0) {
                    biomeWeather.setSnowDepth(Math.max(0, currentDepth - loss));
                }
            }
            // 无条件推进时间戳：任何空闲间隔（干季/满雪/0 深度）都让时间戳跟随真实时间，
            // 避免下次进入累积分支时把整段空闲期一次性补算（瞬间 0→100）
            lastStepTick[index] = gameTime;

            int syncInterval = Config.biomeSyncIntervalTicks();
            if (gameTime % syncInterval == 0 && !level.players().isEmpty()) {
                WeatherManager.sendBiomePacket(level, biomes, level.players());
            }
        } catch (Exception ignored) {}
    }
}
