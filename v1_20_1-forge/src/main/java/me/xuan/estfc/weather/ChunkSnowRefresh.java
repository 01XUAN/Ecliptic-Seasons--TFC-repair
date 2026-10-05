package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import me.xuan.estfc.Config;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 区块加载/更新时的覆雪刷新逻辑（1.20.1 由 {@code ChunkSnowScheduler} 在服务端 tick 驱动）。
 *
 * <p>用 TFC per-position 温度覆写雪的开关决策——比 ES 的 biome 级缓存更精确
 * （同 biome 不同海拔温度不同）。列温度**逐列直接问 TFC**（{@link ClimateQuery#instantTemperature}），
 * 不用"区块中心锚点 + 外推"。</p>
 *
 * <p><b>为什么必须逐列问</b>：TFC 自己的海拔降温在 {@code Helpers.adjustAverageTemperatureByElevation}
 * 里被 {@code Mth.clamp(..., 0, 17.822f)} 封顶（≈110 格海拔），所以高地形的"区块中心温度"
 * 几乎不随中心高度变化；而"中心锚点 + 相对外推"让每个区块都从同一个温度重新起一轮，
 * 陡坡上表现为每过一个区块覆雪图案重复一遍（2026-10-05 实测：相邻区块交界处中心高度差 21 格、
 * 温度只差 0.05°C ⇒ 相邻两列差 3°C / 深度 50）。</p>
 */
public final class ChunkSnowRefresh {

    private ChunkSnowRefresh() {}

    /** TFC 海拔递减率 °C/格（与 TFC 的 0.16225 一致，仅作参考/文档用） */
    public static final float TFC_LAPSE = 0.16225f;

    /** 一个区块刷新过程中不变的共享量。 */
    public record ChunkContext(int biomeSnowDepth, float rainfall) {}

    // 注意：ChunkContext 的构造放在 ChunkSnowScheduler 里。
    // 那里必须用 chunk.getNoiseBiome 取群系，不能用 level.getBiome ——
    // 后者会触发区块加载，在区块加载回调中会自死锁（见 ChunkSnowScheduler 类注释）。

    /** 列温度：直接取该列地表处的 TFC 瞬时温度（绝对场，跨区块连续）。
     *  调用点在服务端 tick（ChunkSnowScheduler），不在区块加载回调里。 */
    public static float columnTemperature(ServerLevel level, int x, int surfaceHeight, int z) {
        return ClimateQuery.instantTemperature(level, new BlockPos(x, surfaceHeight, z));
    }

    /** 既非地图标记方块也不阻挡移动 → 跳过。 */
    public static boolean isSkippable(BlockState state) {
        int flag = MapChecker.getDefaultBlockTypeFlag(state);
        return flag == MapChecker.FLAG_NONE && !state.blocksMotion();
    }

    /**
     * 区块加载：按有效雪深决定加雪/除雪。
     *
     * @param colSnowDepth 有效雪深（调用方用 {@code depthFromTemp} 计算，含温度地板 +
     *                     累积深度 + 降水 gate）。
     */
    public static void applyOnLoad(SnowyStatusKeeper keeper, BlockPos checkPos, BlockState state,
                                   float colTemp, int colSnowDepth, boolean hasSnow) {
        // 零上 → 不应有雪（即使全局 biomeDepth > 0）
        int snowDepth = colTemp > Config.snowMeltTemp() ? 0 : colSnowDepth;
        if (SnowDepthRules.shouldSnow(snowDepth, state.getSeed(checkPos))) {
            if (!hasSnow) {
                keeper.set(checkPos, SnowyStatusKeeper.FLAG_SNOW);
            }
        } else if (hasSnow) {
            keeper.set(checkPos, SnowyStatusKeeper.FLAG_NONE);
        }
    }

    /** 方块变更/其他：只融雪。 */
    public static void applyMeltOnly(SnowyStatusKeeper keeper, BlockPos checkPos,
                                     float colTemp, boolean hasSnow) {
        if (colTemp > Config.snowMeltTemp() && hasSnow) {
            keeper.set(checkPos, SnowyStatusKeeper.FLAG_NONE);
        }
    }
}
