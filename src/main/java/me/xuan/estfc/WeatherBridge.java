package me.xuan.estfc;

import com.teamtea.eclipticseasons.api.misc.IBiomeWeatherProvider;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import me.xuan.estfc.Config;
import me.xuan.estfc.client.TfcUnloadedTempEstimator;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.dries007.tfc.util.tracker.WeatherHelpers;
import net.dries007.tfc.util.tracker.WorldTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;

/**
 * TFC 驱动 ES 雪深的桥接方法。
 *
 * <h3>简洁架构（v3）</h3>
 * <p>两个深度来源，在 {@code calcEffectiveDepth} 中合并：</p>
 * <pre>
 *   tempDepth      = tempToSnowDepth(actualTemp)     — 温度推导（连续渐变，无断层）
 *   biomeDepth     = 服务端缓慢累积 (0→100, 10分钟)   — 降水历史
 *   effectiveDepth = min(100, biomeDepth + tempDepth)
 * </pre>
 *
 * <p>已加载区块：biomeDepth 由 tickWeatherCycle 缓慢驱动（仅在降水+低温时 +1，
 * 仅在零上时 -1）。未加载区块/DH/Voxy：biomeDepth=0，纯温度推导。</p>
 *
 * <p>无 referenceTemp、无状态机、无滑动基准——温度过渡带固定为
 * {@code [snowAppearanceTemp, snowFullCoverageTemp]}。</p>
 */
public class WeatherBridge {

    private static final Logger LOG = LoggerFactory.getLogger("estfc");
    private static boolean LOGGED = false;

    /** 每个 biome index 上次深度 +/-1 的 tick，用于控制累积/融化速度 */
    private static final long[] lastStepTick = new long[256];

    // ==================== 主循环入口 ====================

    /**
     * 每 tick 处理一个群系的雪深增减。
     * 由 {@code WeatherHelpersMixin} 在 TFC 的 {@code advanceWeatherCycle} TAIL 调用。
     */
    public static void tickWeatherCycle(ServerLevel level) {
        try {
            ClimateModel model = getModelIfActive(level);
            if (model == null) return;

            IBiomeWeatherProvider ip = level instanceof IBiomeWeatherProvider ips ? ips : null;
            if (ip == null) return;

            ArrayList<WeatherManager.BiomeWeather> biomes = ip.es$get();
            if (biomes == null || biomes.isEmpty()) return;

            int index = ip.es$getTickBiome();
            WeatherManager.BiomeWeather biomeWeather = biomes.get(index);
            if (biomeWeather == null || biomeWeather.biomeHolder == null) return;

            // 采样玩家位置的温度和降水
            BlockPos playerPos = level.players().isEmpty()
                ? BlockPos.ZERO.above(64)
                : level.players().getFirst().blockPosition();
            if (!level.isLoaded(playerPos)) return;

            float playerTemp = model.getInstantTemperature(level, playerPos);
            boolean precip = isPrecipitating(model, level, playerPos);
            float meltTemp = Config.snowMeltTemp();

            long gameTime = level.getGameTime();
            int currentDepth = biomeWeather.getSnowDepth() & 0xFF;

            if (!LOGGED) {
                LOGGED = true;
                LOG.info("[estfc] tickWeatherCycle: biome={} depth={} precip={} temp={}",
                    biomeWeather.biomeHolder.getKey().location(), currentDepth, precip, playerTemp);
            }

            // ===== 雪深渐变（与 tickChunkIntervalTicks 同步，每次 +1/-1） =====
            int interval = Config.tickChunkIntervalTicks();
            if (precip && playerTemp <= meltTemp && currentDepth < 100) {
                int gain = (int)((gameTime - lastStepTick[index]) / interval);
                if (gain > 0) {
                    lastStepTick[index] += gain * (long)interval;
                    biomeWeather.setSnowDepth(Math.min(100, currentDepth + gain));
                }
            } else if (playerTemp > meltTemp && currentDepth > 0) {
                int loss = (int)((gameTime - lastStepTick[index]) / interval);
                if (loss > 0) {
                    lastStepTick[index] += loss * (long)interval;
                    biomeWeather.setSnowDepth(Math.max(0, currentDepth - loss));
                }
            }

            // ===== 同步到客户端 =====
            int syncInterval = Config.biomeSyncIntervalTicks();
            if (gameTime % syncInterval == 0 && !level.players().isEmpty()) {
                WeatherManager.sendBiomePacket(level, biomes, level.players());
            }

        } catch (Exception e) {}
    }

    // ==================== 温度→雪深 核心转换 ====================

    /**
     * TFC 温度 → 雪深 (0-100)。固定覆盖温度区间线性映射。
     * {@code depth = (appear - temp) / (appear - full) * 100}
     */
    public static int tempToSnowDepth(float temp) {
        float appear = Config.snowAppearanceTemp();
        float full = Config.snowFullCoverageTemp();
        if (temp > appear) return 0;
        if (temp < full) return 100;
        return (int)((appear - temp) / (appear - full) * 100);
    }

    /** 雪深概率：{@code depth > |seed % 100|} → snow */
    public static boolean shouldSnow(int depth, long seed) {
        return depth > Math.abs(seed % 100);
    }

    /**
     * 有效雪深 = max(biomeDepth, tempToSnowDepth(actualTemp))。
     * 标准 [snowAppearanceTemp, snowFullCoverageTemp] 过渡带。
     */
    public static int calcEffectiveDepth(float actualTemp, boolean precipitating,
                                          int biomeDepth) {
        return Math.min(100, Math.max(biomeDepth, tempToSnowDepth(actualTemp)));
    }

    // ==================== 降水检测 ====================

    private static long cachedPrecipCalTick = -1;
    private static boolean cachedPrecipResult;

    /** 客户端兼容版——日历级降水检测，同一日历 tick 内缓存。 */
    public static boolean isPrecipitating(Level level) {
        long calTicks = Calendars.get(level).getCalendarTicks();
        if (calTicks == cachedPrecipCalTick) return cachedPrecipResult;
        cachedPrecipCalTick = calTicks;
        ClimateModel model = Climate.get(level);
        cachedPrecipResult = model.supportsRain() && model.getRain(calTicks) > 0;
        return cachedPrecipResult;
    }

    /** 服务端版——日历 + per-position 降水检测 */
    public static boolean isPrecipitating(ServerLevel level, BlockPos pos) {
        ClimateModel model = getModelIfActive(level);
        if (model == null) return false;
        return isPrecipitating(model, level, pos);
    }

    /** 重用已有 model */
    public static boolean isPrecipitating(ClimateModel model, ServerLevel level, BlockPos pos) {
        long calTicks = Calendars.get(level).getCalendarTicks();
        float rainIntensity = model.getRain(calTicks);
        float rainfall = model.getInstantRainfall(level, pos);
        return WeatherHelpers.isPrecipitating(rainIntensity, rainfall);
    }

    // ==================== 辅助方法 ====================

    @Nullable
    public static ClimateModel getModelIfActive(Level level) {
        ClimateModel model = Climate.get(level);
        if (!model.supportsRain()) return null;
        if (!WorldTracker.get(level).isWeatherEnabled()) return null;
        return model;
    }

    /** 服务端：TFC 瞬时温度 → 雪深 */
    public static int calcSnowDepth(ServerLevel level, BlockPos pos) {
        ClimateModel model = getModelIfActive(level);
        return model != null ? calcSnowDepth(model, level, pos) : 0;
    }

    /** 重用已有 model */
    public static int calcSnowDepth(ClimateModel model, ServerLevel level, BlockPos pos) {
        return tempToSnowDepth(model.getInstantTemperature(level, pos));
    }

    /** 客户端：TFC 温度（含未加载区块估算） → 雪深 */
    public static int calcSnowDepthClient(Level level, ClimateModel model, BlockPos pos) {
        float temp = TfcUnloadedTempEstimator.getCorrectedTemperature(level, model, pos);
        return tempToSnowDepth(temp);
    }

    /**
     * 客户端远景（DH/Voxy）：零上强制无雪（Voxy 无 tickChunk 融雪逻辑），
     * 零下与已加载区块同一公式。
     */
    public static int calcSnowDepthClient(Level level, ClimateModel model, BlockPos pos,
                                           int biomeDepth) {
        float temp = TfcUnloadedTempEstimator.getCorrectedTemperature(level, model, pos);
        if (temp > Config.snowMeltTemp()) return 0;
        return calcEffectiveDepth(temp, isPrecipitating(level), biomeDepth);
    }

    /**
     * 查找指定位置的 biome 雪深。单条目缓存 + 列表变更失效。
     * @return biome 累积雪深 0-100，查不到返回 0
     */
    public static int getBiomeDepthAt(Level level, BlockPos pos) {
        try {
            var biomes = WeatherManager.getBiomeList(level);
            var key = level.getBiome(pos).getKey();
            if (biomes != null) {
                for (var bw : biomes) {
                    if (bw.biomeHolder != null && bw.biomeHolder.is(key)) {
                        return bw.getSnowDepth() & 0xFF;
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
