package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper;
import me.xuan.estfc.Config;
import net.dries007.tfc.util.climate.OverworldClimateModel;
import net.dries007.tfc.world.chunkdata.ChunkData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 区块加载/更新时的覆雪刷新逻辑（1.20.1 由 {@code ChunkSnowScheduler} 在服务端 tick 驱动）。
 *
 * <p>区块级只取一次 TFC 瞬时温度作为「月温 + 日温」基，海拔项逐列按 TFC 公式修正；
 * 不再 256 列各查一次 TFC，也不用"无封顶线性外推"。</p>
 *
 * <p><b>为什么还不能只插值最终覆雪率</b>：TFC 的海拔降温被
 * {@code clamp(..., 0, 17.822f)} 封顶（≈110 格）。只插值最终覆雪率/不按列封顶修正，
 * 会让每个区块从中心重新起一轮，陡坡上表现为覆雪图案重复
 * （2026-10-05 实测：相邻区块中心高度差 21 格、温度只差 0.05°C ⇒ 相邻两列差 3°C / 深度 50）。</p>
 */
public final class ChunkSnowRefresh {

    private ChunkSnowRefresh() {}

    /** TFC 海拔递减率 °C/格（与 TFC 的 0.16225 一致，仅作参考/文档用） */
    public static final float TFC_LAPSE = 0.16225f;

    /** 区块级温度基：月温 + 日温在 16×16 内视为常数，海拔项逐列修正。 */
    public record ChunkTemperature(ChunkData data, float monthDaily) {
        public float at(ServerLevel level, int x, int y, int z) {
            if (data == ChunkData.EMPTY || y <= OverworldClimateModel.SEA_LEVEL) {
                return ClimateQuery.instantTemperature(level, new BlockPos(x, y, z));
            }
            float avgSea = data.getAverageTemp(x, z);
            return avgSea + monthDaily
                - Mth.clamp((y - OverworldClimateModel.SEA_LEVEL) * 0.16225f, 0f, 17.822f);
        }
    }

    /** 一个区块刷新过程中不变的共享量。 */
    public record ChunkContext(int coverage, float rainfall, ChunkTemperature temperature) {}

    // 注意：ChunkContext 的构造放在 ChunkSnowScheduler 里。
    // 那里必须用 chunk.getNoiseBiome 取群系，不能用 level.getBiome ——
    // 后者会触发区块加载，在区块加载回调中会自死锁（见 ChunkSnowScheduler 类注释）。

    /** 既非地图标记方块也不阻挡移动 → 跳过。 */
    public static boolean isSkippable(BlockState state) {
        int flag = MapChecker.getDefaultBlockTypeFlag(state);
        return flag == MapChecker.FLAG_NONE && !state.blocksMotion();
    }

    /**
     * 区块加载：按有效雪深决定加雪/除雪。
     *
     * @param colSnowDepth 当前覆盖率（温度高于融雪温度时为 0）。
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
