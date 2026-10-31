package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.api.misc.IBiomeWeatherProvider;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import me.xuan.estfc.Config;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群系雪深主循环：由 TFC advanceWeatherCycle 之后驱动，
 * 按 tick 间隔缓慢累积/消融 ES 群系雪深并定期同步客户端。
 */
public final class WeatherCycleService {

    private WeatherCycleService() {}

    /**
     * 各维度的群系步进时间戳。按维度分键（不同维度各有一张群系表，索引不通用）。
     */
    private static final Map<ResourceKey<Level>, long[]> LAST_STEP_TICK = new ConcurrentHashMap<>();

    /** 单次最多补 100 步，防止长时间未处理/空服回来时一次冲满。 */
    private static final long MAX_STEPS = 100L;

    /** 一个区块 16×16 列。 */
    private static final float SAMPLES_PER_CHUNK = 256f;
    /** vanilla 降水随机 tick 的 1/48 守卫。 */
    private static final int PRECIPITATION_GATE = 48;

    private static long[] stepTicks(ServerLevel level, int size) {
        long now = level.getGameTime();
        return LAST_STEP_TICK.compute(level.dimension(), (key, arr) -> {
            if (arr == null) {
                long[] created = new long[size];
                Arrays.fill(created, now);
                return created;
            }
            if (arr.length < size) {
                long[] grown = Arrays.copyOf(arr, size);
                Arrays.fill(grown, arr.length, size, now);
                return grown;
            }
            return arr;
        });
    }

    /** 服务器停止/换世界时清空。 */
    public static void clear() {
        LAST_STEP_TICK.clear();
    }

    public static void tickWeatherCycle(ServerLevel level) {
        try {
            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) {
                LAST_STEP_TICK.remove(level.dimension());
                return;
            }

            IBiomeWeatherProvider ip = level instanceof IBiomeWeatherProvider ips ? ips : null;
            if (ip == null) {
                LAST_STEP_TICK.remove(level.dimension());
                return;
            }

            ArrayList<WeatherManager.BiomeWeather> biomes = ip.es$get();
            if (biomes == null || biomes.isEmpty()) {
                LAST_STEP_TICK.remove(level.dimension());
                return;
            }

            long[] lastStepTick = stepTicks(level, biomes.size());
            int index = ip.es$getTickBiome();
            if (index < 0 || index >= biomes.size()) return;

            long gameTime = level.getGameTime();
            WeatherManager.BiomeWeather biomeWeather = biomes.get(index);
            if (biomeWeather == null || biomeWeather.biomeHolder == null) {
                lastStepTick[index] = gameTime;
                return;
            }

            // 用该群系自己的代表点采样，不用某个玩家的位置代表所有群系
            Biome biome = biomeWeather.biomeHolder.value();
            BlockPos samplePos = BiomeSampleCache.get(level, biome);
            if (samplePos == null || !level.isLoaded(samplePos)) {
                lastStepTick[index] = gameTime;
                return;
            }

            BiomeSnowSeed.ensureSeeded(level, biome, samplePos);

            float sampleTemp = ClimateQuery.instantTemperature(level, samplePos);
            float rainfall = ClimateQuery.averageRainfall(level, samplePos);
            boolean precipitating = ClimateQuery.isPrecipitating(level, samplePos);
            float meltTemp = Config.snowMeltTemp();
            float maxCoverage = SnowDepthRules.maxCoverage(rainfall);

            Float stored = BiomeCoverageCache.get(level, biome);
            float coverage = stored != null ? stored : (biomeWeather.getSnowDepth() & 0xFF);

            int interval = Config.tickChunkIntervalTicks();
            long elapsed = Math.max(0L, gameTime - lastStepTick[index]);
            int step = (int) Math.min(MAX_STEPS, elapsed / interval);
            boolean accumulating = precipitating && sampleTemp <= meltTemp
                && maxCoverage > 0f && coverage < 100f;
            boolean melting = sampleTemp > meltTemp && coverage > 1f;

            if (accumulating || melting) {
                if (step > 0) {
                    BiomeCoverageCache.setClimateOnly(level, biome, false);
                    float stepFraction = step / SAMPLES_PER_CHUNK;
                    if (accumulating) {
                        coverage = Math.min(100f,
                            coverage + (100f - coverage) * (maxCoverage / 100f) * stepFraction);
                    } else {
                        coverage = Math.max(1f, coverage * (1f - stepFraction));
                    }
                    // 只推进已消费的时间，保留不足一步的余量；
                    // 到达单次上限时直接对齐当前时间，避免积压
                    lastStepTick[index] = step >= MAX_STEPS
                        ? gameTime
                        : lastStepTick[index] + (long) step * interval;
                }
            } else {
                // 空闲间隔（干季/满雪/最小覆雪）直接对齐当前时间，避免下次一次性补算
                lastStepTick[index] = gameTime;
            }

            // 显示覆盖率按已加载区块的实际随机采样速率追目标：
            // 每 interval 只占 1/256，且只有 randomTickSpeed/48 的概率真正调用 tickChunk。
            Float displayStored = BiomeCoverageCache.getDisplay(level, biome);
            float display = displayStored != null ? displayStored : coverage;
            if (!accumulating && !melting) {
                // 雪停/非降水：目标冻结在当前显示值，避免停雪后继续往旧目标补雪
                coverage = display;
            }
            int randomTickSpeed = level.getGameRules().getInt(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING);
            float gateFactor = randomTickSpeed <= 0 ? 1f
                : Math.max(1f, PRECIPITATION_GATE / (float) randomTickSpeed);
            float maxDisplayStep = 100f * step / (SAMPLES_PER_CHUNK * gateFactor);
            if (maxDisplayStep > 0f) {
                display += Math.max(-maxDisplayStep, Math.min(maxDisplayStep, coverage - display));
            }
            BiomeCoverageCache.set(level, biome, coverage);
            BiomeCoverageCache.setDisplay(level, biome, display);
            int rounded = Math.max(0, Math.round(display));
            if ((biomeWeather.getSnowDepth() & 0xFF) != rounded) {
                biomeWeather.setSnowDepth(rounded);
            }

            int syncInterval = Config.biomeSyncIntervalTicks();
            if (gameTime % syncInterval == 0 && !level.players().isEmpty()) {
                WeatherManager.sendBiomePacket(level, biomes, level.players());
            }
        } catch (Exception ignored) {}
    }
}
