package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.api.util.EclipticUtil;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyMapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import com.teamtea.eclipticseasons.common.core.snow.WeatherStatusKeeper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashSet;
import java.util.Set;

/**
 * 实时逐块覆雪 pass（服务端）。1.20.1 专用：ES 自己的驱动点在 1.20.1 上不执行，见下。
 *
 * <h3>为什么 1.21.1 不需要这个类</h3>
 * 两版 ES 都是在 {@code ServerLevel.tickChunk} 里、用 {@code ordinal = 1} 包住
 * {@code getBlockRandomPos} 来驱动 {@code SnowyStatusKeeper.tickChunk} 的，但那个 ordinal
 * 在 MC 两版里指向不同的代码块：
 * <ul>
 * <li>MC 1.20.1 的序号 1 = {@code iceandsnow} 块（守卫 {@code random.nextInt(16) == 0}）；</li>
 * <li>MC 1.21.1 的序号 1 = {@code tickPrecipitation} 循环（守卫
 * {@code for k < randomTickSpeed { random.nextInt(48) == 0 }}）。</li>
 * </ul>
 * 两边期望命中率都是 ~1/16 每区块每 tick，但 TFC 1.20.1 的 {@code ServerLevelMixin}
 * 用 {@code @Redirect} 把 1.20.1 那个 {@code nextInt(16)} 强改成 1
 * （{@code enableVanillaWeatherEffects=false} 时），使 {@code iceandsnow} 块永不执行
 * → ES 的实时链路整条死掉，表现为「只有区块重载才变雪」。TFC 1.21.1 没有这个 redirect
 * （它取消的是 {@code tickPrecipitation} 本体，不影响 ES 包的那个表达式），所以 1.21.1 正常。
 *
 * <h3>这里的做法：照抄 1.21.1 的统计过程</h3>
 * 不自己发明采样：每个节流 tick，对玩家周围（模拟距离内）每个已加载区块，跑一遍 1.21.1
 * 那个守卫 —— {@code for k < randomTickSpeed: if (level.random.nextInt(48) == 0) 一次 pass}。
 * 于是每区块每 tick 的 pass 数与 1.21.1 同分布，同一个 {@code tickChunkIntervalTicks}
 * 就是同一个速度。
 */
public final class ChunkSnowTicker {

    /** 1.21.1 里那层守卫的概率分母（vanilla {@code tickPrecipitation} 的 {@code nextInt(48)}） */
    private static final int PRECIPITATION_GATE = 48;

    private ChunkSnowTicker() {}

    /** 由 @Mod 入口在服务端 LevelTickEvent END 调用（每 tick）。 */
    public static void tickLevel(ServerLevel level) {
        if (level.players().isEmpty()) return;
        // 与 SnowyStatusKeeperMixin 的节流同一条件：非间隔期整段跳过，连区块查找都省掉
        if (level.getGameTime() % me.xuan.estfc.Config.tickChunkIntervalTicks() != 0) return;

        int randomTickSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        if (randomTickSpeed <= 0) return;

        int radius = Math.max(1, level.getServer().getPlayerList().getSimulationDistance());
        Set<Long> visited = new HashSet<>();

        for (Player player : level.players()) {
            int originX = player.chunkPosition().x - radius;
            int originZ = player.chunkPosition().z - radius;

            for (int dx = 0; dx <= radius * 2; dx++) {
                for (int dz = 0; dz <= radius * 2; dz++) {
                    int cx = originX + dx;
                    int cz = originZ + dz;
                    if (!visited.add(ChunkPos.asLong(cx, cz))) continue;

                    ChunkAccess chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (!(chunk instanceof LevelChunk levelChunk)) continue;

                    // 复制 1.21.1 的守卫：randomTickSpeed 次尝试，每次 1/48 概率落一次 pass
                    for (int k = 0; k < randomTickSpeed; k++) {
                        if (level.random.nextInt(PRECIPITATION_GATE) == 0) {
                            tickChunk(level, levelChunk);
                        }
                    }
                }
            }
        }
    }

    /** 单个区块的一次 pass：采一列交给 ES 判定，再把变更推给客户端。 */
    public static void tickChunk(ServerLevel level, LevelChunk chunk) {
        try {
            if (!EclipticUtil.canSnowyBlockInteract()) return;

            ChunkInfoMap chunkMap = MapChecker.getChunkInfoMapOrCreate(level, chunk.getPos());
            if (chunkMap == null) return;

            SnowyStatusKeeper keeper = SnowyMapChecker.getSnowyStatusKeeper(chunk);
            WeatherStatusKeeper weatherKeeper = SnowyMapChecker.getWeatherStatusKeeper(chunk);

            // 采样位置只用来定位区块内某一列，y 由 tickChunk 自行改写到地表附近
            BlockPos samplePos = new BlockPos(
                chunk.getPos().getMinBlockX() + level.random.nextInt(16),
                0,
                chunk.getPos().getMinBlockZ() + level.random.nextInt(16));

            // 节流与深度替换在 SnowyStatusKeeperMixin 里，与 ES 自己调用时走同一条路径
            keeper.tickChunk(level, chunk, chunk.getPos(), samplePos, chunkMap, weatherKeeper);

            // ES 的发送绑在它自己那条被 TFC 掐掉的调用链上，这里必须主动推
            keeper.updateAndSend(level, chunk);
            weatherKeeper.updateAndSend(level, chunk);
        } catch (Exception ignored) {
            // ES/TFC 任意一方不可用时跳过
        }
    }
}
