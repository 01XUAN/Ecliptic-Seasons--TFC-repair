package me.xuan.estfc.net;

import me.xuan.estfc.Config;
import me.xuan.estfc.client.RegionClimateCache;
import net.dries007.tfc.world.ChunkGeneratorExtension;
import net.dries007.tfc.world.chunkdata.ChunkData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 区域气候点（海平面温度 + 表面高度 + 年均降水，128 格网格）同步。
 * 服务端确定性生成（纯种子函数、时不变、不加载区块）→ 客户端缓存插值。
 * 计算在独立线程池异步执行，主线程只入队。
 */
public final class RegionClimatePayloads {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(new ResourceLocation("estfc", "region_climate"))
        .networkProtocolVersion(() -> PROTOCOL)
        .clientAcceptedVersions(PROTOCOL::equals)
        .serverAcceptedVersions(PROTOCOL::equals)
        .simpleChannel();

    /** 服务端生成缓存上限（满则清空重算） */
    private static final int SERVER_CACHE_CAP = 65536;
    /** 服务端待计算队列上限（满则丢弃新请求，客户端下轮重发） */
    private static final int SERVER_PENDING_CAP = 32768;

    private record RegionPoint(float temp, int height, float rainfall) {}

    private static final ConcurrentHashMap<Long, RegionPoint> SERVER_POINTS = new ConcurrentHashMap<>();

    /** gridKey → 请求玩家 UUID 集合（同一格子的所有请求者都要收到响应） */
    private static final ConcurrentHashMap<Long, Set<UUID>> SERVER_PENDING = new ConcurrentHashMap<>();

    /** 计算中集合：提交时加入、完成时移除——防止同一批点无限重新入队 */
    private static final Set<Long> SERVER_IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** 世界代际：换世界后旧 worker 的结果直接丢弃 */
    private static volatile long cacheGeneration = 0L;

    /**
     * 服务端计算线程池（懒创建，线程数随配置可调，上限钳制到机器核数）。
     * ChunkData 生成与 getBaseHeight 都是 TFC worldgen 多线程路径，可离主线程。
     */
    private static volatile ExecutorService serverWorker = null;
    private static volatile int serverWorkerSize = 0;

    private RegionClimatePayloads() {}

    public record RegionClimateRequest(int gridX, int gridZ) {
        static void encode(RegionClimateRequest msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.gridX());
            buf.writeVarInt(msg.gridZ());
        }

        static RegionClimateRequest decode(FriendlyByteBuf buf) {
            return new RegionClimateRequest(buf.readVarInt(), buf.readVarInt());
        }
    }

    public record RegionClimateResponse(int gridX, int gridZ, float temperature, int surfaceHeight, float rainfall) {
        static void encode(RegionClimateResponse msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.gridX());
            buf.writeVarInt(msg.gridZ());
            buf.writeFloat(msg.temperature());
            buf.writeVarInt(msg.surfaceHeight());
            buf.writeFloat(msg.rainfall());
        }

        static RegionClimateResponse decode(FriendlyByteBuf buf) {
            return new RegionClimateResponse(buf.readVarInt(), buf.readVarInt(),
                buf.readFloat(), buf.readVarInt(), buf.readFloat());
        }
    }

    /** 由 mod 入口注册（mod 事件总线 FMLCommonSetupEvent）。 */
    @SuppressWarnings("removal")
    public static void register() {
        CHANNEL.messageBuilder(RegionClimateRequest.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder(RegionClimateRequest::encode)
            .decoder(RegionClimateRequest::decode)
            .consumerMainThread((msg, ctx) -> {
                handleRequest(msg, ctx.get().getSender());
                ctx.get().setPacketHandled(true);
            })
            .add();

        CHANNEL.messageBuilder(RegionClimateResponse.class, 1, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(RegionClimateResponse::encode)
            .decoder(RegionClimateResponse::decode)
            .consumerMainThread((msg, ctx) -> {
                handleResponse(msg);
                ctx.get().setPacketHandled(true);
            })
            .add();
    }

    /** 客户端 → 服务端（主线程调用）。 */
    public static void sendRequest(RegionClimateRequest request) {
        CHANNEL.sendToServer(request);
    }

    /** 服务端（主线程）：入队，由 onServerTick 按预算提交计算。 */
    private static void handleRequest(RegionClimateRequest payload, @Nullable ServerPlayer player) {
        if (player == null) return;

        long key = gridKey(payload.gridX(), payload.gridZ());
        Set<UUID> requesters = SERVER_PENDING.get(key);
        if (requesters != null) {
            requesters.add(player.getUUID());
        } else if (SERVER_PENDING.size() < SERVER_PENDING_CAP) {
            SERVER_PENDING.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(player.getUUID());
        }
    }

    /** 客户端（主线程）：写入缓存。 */
    private static void handleResponse(RegionClimateResponse payload) {
        RegionClimateCache.onRegionClimate(payload.gridX(), payload.gridZ(),
            payload.temperature(), payload.surfaceHeight(), payload.rainfall());
    }

    /** 服务端每 tick（END 相位）：按预算把队列中的区域点提交给 worker 线程。 */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = event.getServer();
        int budget = Config.dhTempServerSubmitPerTick();
        ExecutorService worker = serverWorker();
        long generation = cacheGeneration;
        for (var it = SERVER_PENDING.entrySet().iterator(); it.hasNext() && budget > 0; ) {
            var entry = it.next();
            long key = entry.getKey();
            if (SERVER_IN_FLIGHT.contains(key)) continue; // 计算中：留在队列，完成后再提交

            Set<UUID> requesters = entry.getValue();
            ServerLevel level = findOverworldLevel(server, requesters);
            if (level == null) {
                it.remove();
                continue;
            }

            it.remove();
            budget--;

            int gridX = (int) (key >> 32);
            int gridZ = (int) key;
            SERVER_IN_FLIGHT.add(key);
            worker.submit(() -> {
                try {
                    RegionPoint point = computeRegion(level, gridX, gridZ, generation);
                    if (point != null) {
                        // 回主线程发包（SimpleChannel 非线程安全）
                        server.execute(() -> {
                            for (UUID uuid : requesters) {
                                ServerPlayer player = onlineOverworldPlayer(server, uuid);
                                if (player == null) continue;
                                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                                    new RegionClimateResponse(gridX, gridZ, point.temp(), point.height(), point.rainfall()));
                            }
                        });
                    }
                } finally {
                    SERVER_IN_FLIGHT.remove(key);
                }
            });
        }
    }

    /** 服务器停止时清空缓存（单机换世界：旧世界数据不能复用）。 */
    public static void clearServerCaches() {
        cacheGeneration++;
        SERVER_POINTS.clear();
        SERVER_PENDING.clear();
        SERVER_IN_FLIGHT.clear();
    }

    /** 在线、连接正常、位于主世界的玩家；否则 null。 */
    @Nullable
    private static ServerPlayer onlineOverworldPlayer(MinecraftServer server, UUID uuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null || player.connection == null
            || !player.connection.connection.isConnected()
            || player.serverLevel().dimension() != Level.OVERWORLD) {
            return null;
        }
        return player;
    }

    /** 找一个请求者所在的主世界；没有在线主世界玩家时返回 null。 */
    @Nullable
    private static ServerLevel findOverworldLevel(MinecraftServer server, Set<UUID> requesters) {
        for (UUID uuid : requesters) {
            ServerPlayer player = onlineOverworldPlayer(server, uuid);
            if (player != null) {
                return player.serverLevel();
            }
        }
        return null;
    }

    /** 确定性生成网格点（温度 + 表面高度 + 降水）。不加载区块；非主世界/非 TFC 生成器 → null。 */
    @Nullable
    private static RegionPoint computeRegion(ServerLevel level, int gridX, int gridZ, long generation) {
        try {
            if (generation != cacheGeneration) return null;
            if (level.dimension() != Level.OVERWORLD) return null;
            if (!(level.getChunkSource().getGenerator() instanceof ChunkGeneratorExtension extension)) return null;

            long key = gridKey(gridX, gridZ);
            RegionPoint cached = SERVER_POINTS.get(key);
            if (cached != null) return cached;

            int blockX = gridX * 128;
            int blockZ = gridZ * 128;

            ChunkData data = extension.chunkDataProvider()
                .createAndGeneratePartial(new ChunkPos(gridX * 8, gridZ * 8));
            float temperature = data.getAverageTemp(blockX, blockZ);
            float rainfall = data.getRainfall(blockX, blockZ);

            // TFCChunkGenerator.getBaseHeight 忽略 randomState（用自身地形填充器），传 null
            int height = extension.self().getBaseHeight(
                blockX, blockZ, Heightmap.Types.WORLD_SURFACE, level, null);

            if (generation != cacheGeneration) return null;
            RegionPoint point = new RegionPoint(temperature, height, rainfall);
            if (SERVER_POINTS.size() > SERVER_CACHE_CAP) SERVER_POINTS.clear();
            SERVER_POINTS.put(key, point);
            return point;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ExecutorService serverWorker() {
        int size = Math.min(Config.dhTempServerThreads(),
            Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = serverWorker;
        if (pool != null && serverWorkerSize == size) return pool;

        synchronized (RegionClimatePayloads.class) {
            if (serverWorker != null && serverWorkerSize == size) return serverWorker;
            if (serverWorker != null) serverWorker.shutdown(); // 配置变更：旧池收尾，新池接管
            serverWorker = Executors.newFixedThreadPool(size, r -> {
                Thread t = new Thread(r, "estfc-region-climate");
                t.setDaemon(true);
                return t;
            });
            serverWorkerSize = size;
            return serverWorker;
        }
    }

    /** 128 格网格点 key（客户端/服务端共用同一打包）。 */
    public static long gridKey(int gridX, int gridZ) {
        return ((long) gridX << 32) | (gridZ & 0xFFFFFFFFL);
    }
}
