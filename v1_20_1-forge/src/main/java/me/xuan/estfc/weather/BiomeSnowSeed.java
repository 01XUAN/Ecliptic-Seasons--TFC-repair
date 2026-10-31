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
 * 播种后雪深至少为 1（0 只作为“未初始化”哨兵），因此之后不会再退回气候兜底。
 */
public final class BiomeSnowSeed {

    private record Key(ResourceKey<Level> dimension, Biome biome) {}

    private static final Set<Key> SEEDED = ConcurrentHashMap.newKeySet();

    private BiomeSnowSeed() {}

    /** 已播种/无需播种时返回；尚未发生过降雪/融雪时允许按当前温度刷新种子。 */
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

        boolean first = SEEDED.add(key);
        if (!first && !BiomeCoverageCache.isClimateOnly(level, biome)) return;

        // 存档里已有深度：>1 视为历史；0/1 视为仅播种/已融尽，允许按当前气候刷新
        int current = weather.getSnowDepth() & 0xFF;
        if (first && current > 1) {
            BiomeCoverageCache.set(level, biome, current);
            BiomeCoverageCache.setDisplay(level, biome, current);
            BiomeCoverageCache.setClimateOnly(level, biome, false);
            return;
        }

        seedClimate(level, biome, pos, model, weather);
    }

    private static void seedClimate(ServerLevel level, Biome biome, BlockPos pos,
                                    OverworldClimateModel model, WeatherManager.BiomeWeather weather) {
        float temp = ClimateQuery.instantTemperature(level, pos);
        float rainfall = ClimateQuery.averageRainfall(level, pos);
        int seed = SnowDepthRules.climateSeed(temp, rainfall);
        // 潮湿群系至少留 1 作为“已初始化”哨兵；干旱(0)保持 0
        if (SnowDepthRules.rainfallFactor(rainfall) > 0f) seed = Math.max(1, seed);
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
