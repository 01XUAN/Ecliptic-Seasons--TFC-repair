package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import net.dries007.tfc.util.climate.OverworldClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个群系首次获得代表点时，用当地温度播种一次雪深。
 * 播种后由 {@link #SEEDED} 与 climateOnly 标记跟踪初始化，不再把 0 当哨兵。
 */
public final class BiomeSnowSeed {

    private record Key(ResourceKey<Level> dimension, Biome biome) {}

    private static final Set<Key> SEEDED = ConcurrentHashMap.newKeySet();

    private BiomeSnowSeed() {}

    /** 每个群系只播种一次；后续覆雪率由 {@link WeatherCycleService} 推进。 */
    public static void ensureSeeded(ServerLevel level, Biome biome, BlockPos pos) {
        if (biome == null || pos == null || !level.isLoaded(pos)) return;

        BiomeSampleCache.record(level, biome, pos);
        Key key = new Key(level.dimension(), biome);
        OverworldClimateModel model = ClimateQuery.getOverworldModelIfActive(level);
        if (model == null) {
            SEEDED.remove(key);
            return;
        }

        WeatherManager.BiomeWeather weather = WeatherManager.getBiomeWeather(level, biome);
        if (weather == null) {
            SEEDED.remove(key);
            return;
        }

        if (!SEEDED.add(key)) return;

        // 存档里已有深度：>1 视为历史；0/1 视为仅播种/已融尽，按当前气候播种
        int current = weather.getSnowDepth() & 0xFF;
        if (current > 1) {
            BiomeCoverageCache.set(level, biome, current);
            BiomeCoverageCache.setDisplay(level, biome, current);
            BiomeCoverageCache.setClimateOnly(level, biome, false);
            return;
        }

        // 用稳定代表点播种，不随区块加载换点
        BlockPos samplePos = BiomeSampleCache.get(level, biome);
        seedClimate(level, biome, samplePos != null ? samplePos : pos, model, weather);
    }

    private static void seedClimate(ServerLevel level, Biome biome, BlockPos pos,
                                    OverworldClimateModel model, WeatherManager.BiomeWeather weather) {
        float temp = ClimateQuery.instantTemperature(level, pos);
        float rainfall = ClimateQuery.averageRainfall(level, pos);
        int seed = SnowDepthRules.climateSeed(temp, rainfall);
        BiomeCoverageCache.set(level, biome, seed);
        BiomeCoverageCache.setDisplay(level, biome, seed);
        BiomeCoverageCache.setClimateOnly(level, biome, true);
        weather.setSnowDepth(seed);
    }

    /** 服务器停止/换世界时清空。 */
    public static void clear() {
        SEEDED.clear();
    }
}
