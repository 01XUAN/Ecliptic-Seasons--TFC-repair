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
 * 区块加载/更新时的覆雪刷新逻辑（由 SnowyMapCheckerMixin 驱动）。
 *
 * <p>用 TFC per-position 温度覆写雪的开关决策——比 ES 的 biome 级缓存更精确
 * （同 biome 不同海拔温度不同）。列温度取区块中心地表温度按海拔递减率外推，
 * 保证采样点在已加载 chunk 内，避免 ChunkData.EMPTY 假 10°C。</p>
 */
public final class ChunkSnowRefresh {

    private ChunkSnowRefresh() {}

    /** TFC 海拔递减率 °C/格（更高 = 更冷） */
    public static final float TFC_LAPSE = 0.16225f;

    /** 一个区块刷新过程中不变的共享量。 */
    public record ChunkContext(float baseTemp, int centerSurface, int biomeSnowDepth, float rainfall) {}

    // 注意：ChunkContext 的构造放在 ChunkSnowScheduler 里。
    // 那里必须用 chunk.getNoiseBiome 取群系，不能用 level.getBiome ——
    // 后者会触发区块加载，在区块加载回调中会自死锁（见 ChunkSnowScheduler 类注释）。

    /** O(1) 列温度 = chunk 中心地表温度 - 海拔差 × 递减率 */
    public static float columnTemperature(ChunkContext ctx, int surfaceHeight) {
        return ctx.baseTemp() - (surfaceHeight - ctx.centerSurface()) * TFC_LAPSE;
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
    public static boolean applyOnLoad(SnowyStatusKeeper keeper, BlockPos checkPos, BlockState state,
                                      float colTemp, int colSnowDepth, boolean hasSnow) {
        // 零上 → 不应有雪（即使全局 biomeDepth > 0）
        int snowDepth = colTemp > Config.snowMeltTemp() ? 0 : colSnowDepth;
        if (SnowDepthRules.shouldSnow(snowDepth, state.getSeed(checkPos))) {
            if (!hasSnow) {
                keeper.set(checkPos, SnowyStatusKeeper.FLAG_SNOW);
                return true;
            }
        } else if (hasSnow) {
            keeper.set(checkPos, SnowyStatusKeeper.FLAG_NONE);
            return true;
        }
        return false;
    }

    /** 方块变更/其他：只融雪。 */
    public static boolean applyMeltOnly(SnowyStatusKeeper keeper, BlockPos checkPos,
                                        float colTemp, boolean hasSnow) {
        if (colTemp > Config.snowMeltTemp() && hasSnow) {
            keeper.set(checkPos, SnowyStatusKeeper.FLAG_NONE);
            return true;
        }
        return false;
    }
}
