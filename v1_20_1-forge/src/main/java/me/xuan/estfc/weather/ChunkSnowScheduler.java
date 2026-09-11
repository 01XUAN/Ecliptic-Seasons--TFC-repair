package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.teamtea.eclipticseasons.common.core.snow.WeatherStatusKeeper;
import me.xuan.estfc.Config;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区块覆雪刷新的调度队列。
 *
 * <p>ES 的 {@code forceChunkUpdateHeight} 是在 {@code ChunkMap} 的区块加载 future 回调里、
 * 于主线程上执行的。此时若调用任何会走 {@code Level.getBiome}/{@code Level.getChunk}
 * 的 TFC 气候读取，就会把「加载区块」的任务提交给主线程并 park 等待——
 * 而主线程正是自己，构成自死锁（1.21.1 的 TFC 气候读取不经过 ChunkData，所以没这个问题）。</p>
 *
 * <p>因此注入点只入队，真正的刷新挪到服务端 tick 执行：那时不在区块回调中，
 * 且区块已加载完毕，气候读取安全。</p>
 */
public final class ChunkSnowScheduler {

    /** 每 tick 处理的区块上限 */
    private static final int MAX_CHUNKS_PER_TICK = 8;
    /** 待处理队列上限（超出丢弃，区块下次加载会重新入队） */
    private static final int MAX_PENDING = 4096;

    private record Key(ResourceKey<Level> dim, long pos) {}
    private record Entry(ServerLevel level, ChunkInfoMap chunkMap, boolean loadingChunk) {}

    private static final Map<Key, Entry> PENDING = new ConcurrentHashMap<>();

    private ChunkSnowScheduler() {}

    /** 入队（主线程，可能在区块加载回调中）。key 含维度，避免跨维度误用。 */
    public static void enqueue(ServerLevel level, ChunkInfoMap chunkMap, ChunkPos pos, boolean loadingChunk) {
        if (PENDING.size() >= MAX_PENDING) return;
        PENDING.put(new Key(level.dimension(), pos.toLong()), new Entry(level, chunkMap, loadingChunk));
    }

    /** 服务端 tick（END 相位、非区块回调上下文）。 */
    public static void tick(ServerLevel level) {
        if (PENDING.isEmpty()) return;

        int budget = MAX_CHUNKS_PER_TICK;
        for (Iterator<Map.Entry<Key, Entry>> it = PENDING.entrySet().iterator(); it.hasNext() && budget > 0; ) {
            Map.Entry<Key, Entry> e = it.next();
            budget--; // 按「检查过的条目」计数，避免其他维度把循环拖住
            Entry entry = e.getValue();
            if (entry.level() != level) continue;

            it.remove();
            refresh(level, e.getKey().pos(), entry);
        }
    }

    /** 世界卸载/服务器停止时清空。 */
    public static void clear() {
        PENDING.clear();
    }

    private static void refresh(ServerLevel level, long packedKey, Entry entry) {
        boolean loadingChunk = entry.loadingChunk();
        ChunkInfoMap chunkMap = entry.chunkMap();
        ChunkPos pos = new ChunkPos(packedKey);
        ChunkAccess chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) return; // 已卸载，下次加载会重新入队

        try {
            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) return;

            SnowyStatusKeeper keeper = SnowyMapChecker.getSnowyStatusKeeper(chunk);
            WeatherStatusKeeper weatherKeeper = SnowyMapChecker.getWeatherStatusKeeper(chunk);

            ChunkSnowRefresh.ChunkContext ctx = prepare(level, chunk, model);

            int minX = pos.getMinBlockX();
            int minZ = pos.getMinBlockZ();
            int maxX = pos.getMaxBlockX();
            int maxZ = pos.getMaxBlockZ();
            BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();

            // ChunkInfoMap 未初始化时会返回 minY-1 这类哨兵值，夹到世界高度内
            int worldMin = chunk.getMinBuildHeight();
            int worldMax = chunk.getMaxBuildHeight() - 1;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int surfaceHeight = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    int solidHeight = chunkMap.getHeight(x, z);

                    int minY = Math.min(Math.max(solidHeight, worldMin), worldMax);
                    int maxY = Math.min(Math.max(surfaceHeight, worldMin), worldMax);
                    if (maxY < minY) maxY = minY;

                    float colTemp = ChunkSnowRefresh.columnTemperature(ctx, surfaceHeight);
                    int colSnowDepth = SnowDepthRules.depthFromTemp(colTemp, ctx.biomeSnowDepth(), ctx.rainfall());

                    for (int y = minY; y <= maxY; y++) {
                        checkPos.set(x, y, z);
                        BlockState state = chunk.getBlockState(checkPos);

                        if (ChunkSnowRefresh.isSkippable(state)) continue;

                        boolean hasSnow = keeper.isSnowyBlock(checkPos);

                        if (loadingChunk) {
                            ChunkSnowRefresh.applyOnLoad(keeper, checkPos, state, colTemp, colSnowDepth, hasSnow);
                        } else {
                            ChunkSnowRefresh.applyMeltOnly(keeper, checkPos, colTemp, hasSnow);
                        }
                    }
                }
            }

            // 必须在这里主动推送。ES 的发送绑在 forceChunkUpdateHeight 流程上
            // （postAfterChunkUpdate 只在 !loadingChunk 时发；MixinServerLevel.tickChunk 的
            // @Share keeper 也只在本次 tickChunk 走过该流程时才有值）。本类的写入发生在
            // 服务端 tick、ES 流程之外，不主动发的话客户端只有区块重载时才看得到 →
            // 表现为「新加载区块有雪、已加载区块不更新」。
            if (chunk instanceof LevelChunk levelChunk) {
                keeper.updateAndSend(level, levelChunk);
                weatherKeeper.updateAndSend(level, levelChunk);
            }
        } catch (Exception ignored) {
            // TFC 不可用时跳过
        }
    }

    /**
     * 列温度基准。biome 必须从区块本地取（{@code getNoiseBiome}），
     * 不能用 {@code level.getBiome} —— 后者会触发区块加载。
     */
    private static ChunkSnowRefresh.ChunkContext prepare(ServerLevel level, ChunkAccess chunk, ClimateModel model) {
        int midX = chunk.getPos().getMiddleBlockX();
        int midZ = chunk.getPos().getMiddleBlockZ();
        int centerSurface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, midX, midZ);
        BlockPos centerPos = new BlockPos(midX, centerSurface, midZ);

        Biome biome = chunk.getNoiseBiome(
            QuartPos.fromBlock(midX), QuartPos.fromBlock(centerSurface), QuartPos.fromBlock(midZ)).value();
        int biomeSnowDepth = ClimateQuery.getBiomeDepth(level, biome);

        // 区块已加载（上面 getChunkNow 已确认），此处的气候读取不会触发加载
        float baseTemp = ClimateQuery.instantTemperature(level, centerPos);
        float rainfall = ClimateQuery.averageRainfall(level, centerPos);

        return new ChunkSnowRefresh.ChunkContext(baseTemp, centerSurface, biomeSnowDepth, rainfall);
    }
}
