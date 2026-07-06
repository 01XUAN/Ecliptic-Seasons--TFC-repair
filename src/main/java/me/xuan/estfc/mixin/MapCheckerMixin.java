package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import me.xuan.estfc.EstfcConfig;
import me.xuan.estfc.WeatherBridge;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端 shouldSnowAtBiome — DH/Voxy 远景入口。
 *
 * <h3>核心设计</h3>
 * TFC 温度完全由 (z纬度, y海拔, 时间) 决定，与群系类型<b>无关</b>。
 * 客户端始终可用——ChunkData.EMPTY baseline=10°C + 纬度/海拔调整。
 *
 * <p><b>未加载区块偏移</b>：对于 {@code !isLoaded(pos)} 的位置，
 * 温度加上 {@link EstfcConfig#unloadedTempOffset}（默认 +5°C）来抵消
 * ChunkData.EMPTY 的 baseline 偏差，避免温暖纬度错误覆雪。
 *
 * <h3>温度→雪深</h3>
 * 委托 {@link WeatherBridge#tempToSnowDepth(float)}：
 * temp > 0 → 0, temp ∈ [-4,0] → |temp|*25, temp < -4 → 100
 */
@Mixin(value = MapChecker.class, remap = false)
public abstract class MapCheckerMixin {

    @Unique
    private static final Logger LOG = LoggerFactory.getLogger("estfc");

    @Unique
    private static boolean LOGGED_FIRST = false;

    @Unique
    private static int DEBUG_COUNT = 0;

    @Unique
    private static final int DEBUG_LIMIT = 50;

    /**
     * 拦截客户端 shouldSnowAtBiome，由 TFC 温度直接决策。
     * 服务端走 ES 原生（群系列表正确初始化，biome 级雪深由 WeatherBridge.tickWeatherCycle 驱动）。
     */
    @Inject(method = "shouldSnowAtBiome", at = @At("HEAD"), cancellable = true)
    private static void estfc$shouldSnowAtBiome(
        Level level, Biome biome, BlockState state, RandomSource random, long seed, BlockPos mcPos,
        CallbackInfoReturnable<Boolean> cir) {

        // 服务端：ES 原生（群系雪深由 WeatherBridge.tickWeatherCycle 维护）
        if (level instanceof ServerLevel) return;

        // ============ 客户端 ============
        boolean loaded = level.isLoaded(mcPos);

        // TFC 温度 = f(z纬度, y海拔, 日历时间)
        // 客户端始终可用——ChunkData.EMPTY baseline=10°C + 纬度/海拔调整
        try {
            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return; // 非 TFC 世界 → 回退 ES 原生

            float temp = model.getInstantTemperature(level, mcPos);

            // 未加载区块：加温度偏移（默认 +5°C），抵消 ChunkData.EMPTY baseline 偏差
            // 避免 DH 远景在温暖纬度出现不应有的覆雪
            if (!loaded) {
                temp += EstfcConfig.unloadedTempOffset;
            }

            int depth = WeatherBridge.tempToSnowDepth(temp);

            boolean snow = depth > Math.abs(seed % 100);
            cir.setReturnValue(snow);

            debugLog(mcPos, loaded, temp, depth, snow);
        } catch (Exception e) {
            // TFC 不可用 → 回退 ES 原生
            if (!LOGGED_FIRST) {
                LOGGED_FIRST = true;
                LOG.warn("[estfc] TFC unavailable on client, falling back to ES native snow", e);
            }
        }
    }

    @Unique
    private static void debugLog(BlockPos pos, boolean loaded, float temp, int depth, boolean snow) {
        if (DEBUG_COUNT >= DEBUG_LIMIT) return;
        DEBUG_COUNT++;
        LOG.info("[estfc:shouldSnow] #{} ({},{},{}) loaded={} temp={} depth={} -> {}",
            DEBUG_COUNT, pos.getX(), pos.getY(), pos.getZ(), loaded, temp, depth, snow);
    }
}
