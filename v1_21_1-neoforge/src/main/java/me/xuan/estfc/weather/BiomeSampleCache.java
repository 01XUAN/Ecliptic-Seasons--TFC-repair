package me.xuan.estfc.weather;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个群系的代表采样点（本会话内取该群系第一个仍加载的位置）。
 * 雪深累积/融化用该点自己的 TFC 温度与降水，避免拿单个玩家的位置代表所有群系。
 */
public final class BiomeSampleCache {

    private static final Map<ResourceKey<Level>, Map<Biome, BlockPos>> SAMPLES = new ConcurrentHashMap<>();

    private BiomeSampleCache() {}

    /** 记录群系代表点；已有点所在区块仍加载时不换点，避免覆雪率随区块加载跳变。 */
    public static void record(Level level, Biome biome, BlockPos pos) {
        if (biome == null || pos == null || !level.isLoaded(pos)) return;
        Map<Biome, BlockPos> samples = SAMPLES.computeIfAbsent(level.dimension(), key -> new ConcurrentHashMap<>());
        BlockPos previous = samples.get(biome);
        if (previous != null && (previous.equals(pos) || level.isLoaded(previous))) return;
        samples.put(biome, pos.immutable());
    }

    @Nullable
    public static BlockPos get(Level level, Biome biome) {
        Map<Biome, BlockPos> samples = SAMPLES.get(level.dimension());
        return samples == null ? null : samples.get(biome);
    }

    /** 服务器停止/换世界时清空。 */
    public static void clear() {
        SAMPLES.clear();
    }
}
