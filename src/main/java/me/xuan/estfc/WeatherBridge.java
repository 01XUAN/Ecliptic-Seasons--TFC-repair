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

            // ===== 雪深渐变（无状态机——直接按条件判断） =====
            if (precip && playerTemp <= meltTemp && currentDepth < 100) {
                // 降水+低温 → 缓慢积雪
                int step = accumulationStepTicks();
                if (gameTime - lastStepTick[index] >= step) {
                    lastStepTick[index] = gameTime;
                    biomeWeather.setSnowDepth(currentDepth + 1);
                }
            } else if (playerTemp > meltTemp && currentDepth > 0) {
                // 零上 → 缓慢融雪
                int step = meltStepTicks();
                if (gameTime - lastStepTick[index] >= step) {
                    lastStepTick[index] = gameTime;
                    biomeWeather.setSnowDepth(currentDepth - 1);
                }
            }
            // 否则：低温无降水 → 雪深保持（不增不减）

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
     * 计算有效雪深 (0-100)。
     *
     * <p>公式：{@code biomeDepth + tempDepth * (100 - biomeDepth) / 100}
     * 其中 {@code tempDepth = tempToSnowDepth(actualTemp)}。</p>
     *
     * <p>效果：温度差异在积雪初期最明显（冷处先有雪），
     * 随着 biomeDepth 增长逐渐收敛到满雪。
     * <pre>
     *   biomeDepth=10:  -4°C → 10+100×0.9=100,  0°C → 10+0=10
     *   biomeDepth=50:  -4°C → 50+100×0.5=100,  0°C → 50+0=50
     *   biomeDepth=90:  -4°C → 90+100×0.1=100,  0°C → 90+0=90
     * </pre>
     *
     * @param actualTemp    TFC per-position 瞬时温度 (°C)
     * @param precipitating 是否正在降水
     * @param biomeDepth    该 biome 的累积雪深 (0-100)
     * @return 有效雪深 0-100
     */
    public static int calcEffectiveDepth(float actualTemp, boolean precipitating,
                                          int biomeDepth) {
        // 融化中：不加温度贡献，纯 biomeDepth 缓慢递减
        if (actualTemp > Config.snowMeltTemp()) {
            return biomeDepth;
        }
        if (biomeDepth >= 100) return 100;
        int tempDepth = tempToSnowDepth(actualTemp);
        // tempDepth 贡献随 biomeDepth 增长而衰减：越接近满雪，温度差异越小
        int tempContribution = tempDepth * (100 - biomeDepth) / 100;
        return Math.min(100, biomeDepth + tempContribution);
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

    /** 积雪每 +1 深度的 tick 间隔 */
    private static int accumulationStepTicks() {
        float minutes = Config.snowFullTransitionMinutes();
        return Math.max(1, Math.round(minutes * 60f * 20f / 100f));
    }

    /** 融雪每 -1 深度的 tick 间隔 */
    private static int meltStepTicks() {
        float minutes = Config.snowMeltTransitionMinutes();
        return Math.max(1, Math.round(minutes * 60f * 20f / 100f));
    }

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
     * 客户端：含 biome 积雪历史的有效雪深（供 DH/Voxy 远景使用）。
     * 使用与已加载区块相同的加权公式，确保远近一致。
     */
    public static int calcSnowDepthClient(Level level, ClimateModel model, BlockPos pos,
                                           int biomeDepth) {
        float temp = TfcUnloadedTempEstimator.getCorrectedTemperature(level, model, pos);
        return calcEffectiveDepth(temp, isPrecipitating(level), biomeDepth);
    }

    /**
     * 从 level 的 biome 列表中查找指定位置的 biome 雪深。
     * @return biome 累积雪深 0-100，查不到返回 0
     */
    public static int getBiomeDepthAt(Level level, BlockPos pos) {
        try {
            var key = level.getBiome(pos).getKey();
            var biomes = WeatherManager.getBiomeList(level);
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
