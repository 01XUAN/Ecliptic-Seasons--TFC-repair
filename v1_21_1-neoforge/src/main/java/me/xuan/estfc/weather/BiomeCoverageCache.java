package me.xuan.estfc.weather;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个群系的期望覆雪率（0-100，浮点）。与 ES 的“每 tick 采样一个方块”同速率更新：
 * 降水时向 100 逼近，升温时指数衰减。新区块、DH、voxy 直接读这个值，不再需要整区块重刷。
 */
public final class BiomeCoverageCache {

    private static final Map<ResourceKey<Level>, Map<Biome, Float>> COVERAGE = new ConcurrentHashMap<>();
    /** 显示覆盖率：按已加载区块列游标同速追 COVERAGE，供新区块/远景读取。 */
    private static final Map<ResourceKey<Level>, Map<Biome, Float>> DISPLAY = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Map<Biome, Boolean>> CLIMATE_ONLY = new ConcurrentHashMap<>();

    private BiomeCoverageCache() {}

    public static void set(ServerLevel level, Biome biome, float coverage) {
        COVERAGE.computeIfAbsent(level.dimension(), key -> new ConcurrentHashMap<>())
            .put(biome, coverage);
        DISPLAY.computeIfAbsent(level.dimension(), key -> new ConcurrentHashMap<>())
            .putIfAbsent(biome, coverage);
    }

    public static void setDisplay(ServerLevel level, Biome biome, float coverage) {
        DISPLAY.computeIfAbsent(level.dimension(), key -> new ConcurrentHashMap<>())
            .put(biome, coverage);
    }

    @Nullable
    public static Float getDisplay(ServerLevel level, Biome biome) {
        Map<Biome, Float> map = DISPLAY.get(level.dimension());
        Float value = map == null ? null : map.get(biome);
        return value != null ? value : get(level, biome);
    }

    @Nullable
    public static Float get(ServerLevel level, Biome biome) {
        Map<Biome, Float> map = COVERAGE.get(level.dimension());
        return map == null ? null : map.get(biome);
    }

    /** 尚未发生过降雪累积/融化：C 仍可随当前温度种子刷新。 */
    public static void setClimateOnly(ServerLevel level, Biome biome, boolean climateOnly) {
        CLIMATE_ONLY.computeIfAbsent(level.dimension(), key -> new ConcurrentHashMap<>())
            .put(biome, climateOnly);
    }

    public static boolean isClimateOnly(ServerLevel level, Biome biome) {
        Map<Biome, Boolean> map = CLIMATE_ONLY.get(level.dimension());
        return map != null && Boolean.TRUE.equals(map.get(biome));
    }

    public static void clear() {
        COVERAGE.clear();
        DISPLAY.clear();
        CLIMATE_ONLY.clear();
    }
}
