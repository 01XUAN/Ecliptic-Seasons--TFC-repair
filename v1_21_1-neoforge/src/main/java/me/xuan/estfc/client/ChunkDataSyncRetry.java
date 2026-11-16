package me.xuan.estfc.client;

import me.xuan.estfc.Config;
import net.dries007.tfc.network.ChunkWatchPacket;
import net.dries007.tfc.world.chunkdata.ChunkData;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TFC 1.21.1 的 {@link ChunkWatchPacket} 在区块包之前到达时会被丢弃。
 *
 * <p>NeoForge 的 {@code ChunkWatchEvent.Sent} 在 {@code ChunkMap.markChunkPendingToSend} 里触发，
 * 此时区块包还停在 ChunkSender 队列里；TFC 紧接着用 {@code PacketDistributor.sendToPlayer}
 * 直接发出气候包。客户端先收到气候包时 {@code level.getChunk()} 还是 {@code EmptyLevelChunk}，
 * {@code ChunkData.status() == INVALID}，原版 {@code handle()} 直接返回 —— 这块区块之后一直用
 * {@code UNKNOWN_TEMPERATURE = 10°C}。1.20.1 的 TFC 有 {@code CLIENT_CHUNK_QUEUE} 兜底
 * （{@code queueClientChunkDataForLoad} / {@code dequeueClientChunkData}），1.21.1 移植时丢了。</p>
 *
 * <p>这里在客户端按 tick 重试，区块加载后把数据写进 {@link ChunkData}；只处理客户端，
 * 服务端 ChunkData 不受影响。</p>
 */
public final class ChunkDataSyncRetry {

    private static final Logger LOGGER = LoggerFactory.getLogger("estfc");
    /** 最多等待 400 tick（20 秒）；超时说明区块没有再进入视野 */
    private static final int MAX_TICKS = 400;

    private static final class Pending {
        final ChunkWatchPacket packet;
        final WeakReference<Level> level;
        int ticksLeft;

        Pending(ChunkWatchPacket packet, Level level) {
            this.packet = packet;
            this.level = new WeakReference<>(level);
            this.ticksLeft = MAX_TICKS;
        }
    }

    private static final Map<Long, Pending> PENDING = new ConcurrentHashMap<>();
    /** 超时丢弃计数（只用于 debug 日志限流） */
    private static final AtomicInteger DROPPED = new AtomicInteger();
    /** 延迟写入计数（只用于 debug 日志限流） */
    private static final AtomicInteger RETRIED = new AtomicInteger();

    private ChunkDataSyncRetry() {}

    /** 客户端初始化时注册 tick。 */
    public static void init() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
    }

    /** 由 {@code ChunkWatchPacketMixin} 在客户端主线程调用：能立即写入就写，否则入队重试。 */
    public static void receive(ChunkWatchPacket packet) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return; // 与 TFC 原逻辑一致：世界未就绪时丢弃
        if (apply(level, packet)) return;
        PENDING.put(packet.pos().toLong(), new Pending(packet, level));
    }

    /** 客户端 tick：重试等区块加载的气候包。 */
    private static void tick() {
        if (PENDING.isEmpty()) return;
        for (Iterator<Map.Entry<Long, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Pending pending = it.next().getValue();
            Level level = pending.level.get();
            if (level == null) {
                it.remove();
                continue;
            }
            if (apply(level, pending.packet)) {
                it.remove();
                if (Config.debugLogs() && RETRIED.incrementAndGet() <= 8) {
                    LOGGER.info("[estfc] chunk watch packet applied after retry: {} ({} ticks left)",
                        pending.packet.pos(), pending.ticksLeft);
                }
                continue;
            }
            if (--pending.ticksLeft <= 0) {
                it.remove();
                if (Config.debugLogs() && DROPPED.incrementAndGet() <= 8) {
                    LOGGER.warn("[estfc] chunk watch packet dropped after {} ticks: {}",
                        MAX_TICKS, pending.packet.pos());
                }
            }
        }
    }

    /** 区块已加载 → 把气候层写进 ChunkData；未加载返回 false。 */
    private static boolean apply(Level level, ChunkWatchPacket packet) {
        LevelChunk chunk = level.getChunk(packet.pos().x, packet.pos().z);
        ChunkData data = ChunkData.get(chunk);
        if (data.status() == ChunkData.Status.INVALID) return false;
        data.onUpdatePacket(packet.rainfall(), packet.rainVariance(),
            packet.baseGroundwater(), packet.temperature(), packet.forestType());
        return true;
    }
}
