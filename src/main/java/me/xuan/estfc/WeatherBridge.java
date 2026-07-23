package me.xuan.estfc;

import com.teamtea.eclipticseasons.api.misc.IBiomeWeatherProvider;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import me.xuan.estfc.Config;
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
 * TFC 驱动 ES 雪深的桥接方法。从 Mixin 类中分离出来以避免 Mixin 访问限制。
 */
public class WeatherBridge {

    private static final Logger LOG = LoggerFactory.getLogger("estfc");
    private static boolean LOGGED = false;
    private static boolean FIRST_SYNC_DONE = false;
    /** 每个 biome index 上次深度 +1/-1 的 tick，用于控制累积/融化速度 */
    private static final long[] lastStepTick = new long[256];

    /**
     * 每 tick 处理一个群系的雪深更新。
     * 由 {@code WeatherHelpersMixin} 在 TFC 的 {@code advanceWeatherCycle} TAIL 调用。
     *
     * <p>复用 ES 的 {@code IBiomeWeatherProvider} 群系轮转计数器，
     * 全部决策基于 TFC 气候数据。</p>
     *
     * <p>ES 路径：降水状态变化时，调用 {@code SnowyMapChecker.updateAllChunks(level)}
     * 遍历所有已加载 chunk → 每 chunk 走 {@code forceChunkUpdateHeight}
     * → {@code SnowyMapCheckerMixin} 用 TFC per-position 温度重算覆雪
     * → 服务端发 {@code ChunkUpdateMessage} → 客户端 store SnowyRemover → chunk 重编译。</p>
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

            // 每个 biome 需要各自的海拔来获得不同的温度（而不是所有 biome 共享玩家位置的温度）
            // 策略：用玩家 X/Z（保证已加载），按 biome index 变化 Y 来模拟不同海拔
            // → TFC 海拔递减率 (-1.6°C/10m) 确保不同"海拔"的 biome 获得不同深度
            // 降水仍用玩家位置（降水不依赖海拔）
            BlockPos playerPos = level.players().isEmpty()
                ? BlockPos.ZERO.above(64)
                : level.players().getFirst().blockPosition();
            if (!level.isLoaded(playerPos)) return;

            // 不同 biome 分配不同采样海拔 (64~180)，提供温度多样性
            int sampleY = 64 + (index * 17) % 116;
            BlockPos samplePos = playerPos.atY(Math.min(sampleY, level.getMaxBuildHeight() - 1));
            int depth = calcSnowDepth(level, samplePos);
            boolean precip = isPrecipitating(level, playerPos);

            if (!LOGGED) {
                LOGGED = true;
                LOG.info("[estfc] tickWeatherCycle active: biome={} depth={} precip={} sampleY={}",
                    biomeWeather.biomeHolder.getKey().location(), depth, precip, sampleY);
            }

            // 群系雪深逐步累积（速度：每5秒 +speed 深，默认2）
            int currentDepth = biomeWeather.getSnowDepth() & 0xFF;
            if (precip && depth > 0 && currentDepth < depth) {
                float spd = Config.snowAccumulationSpeed();
                int ticksPerStep = spd > 0 ? Math.max(1, Math.round(100f / spd)) : Integer.MAX_VALUE;
                if (level.getGameTime() - lastStepTick[index] >= ticksPerStep) {
                    lastStepTick[index] = level.getGameTime();
                    biomeWeather.setSnowDepth(currentDepth + 1);
                }
            } else if (depth == 0 && currentDepth > 0) {
                float spd = Config.snowMeltSpeed();
                int ticksPerStep = spd > 0 ? Math.max(1, Math.round(100f / spd)) : Integer.MAX_VALUE;
                if (level.getGameTime() - lastStepTick[index] >= ticksPerStep) {
                    lastStepTick[index] = level.getGameTime();
                    biomeWeather.setSnowDepth(currentDepth - 1);
                }
            }
            // 零下 + 无降水 → 不动（雪不会在零下消失！）

            // 首次运行时立即同步一次，避免 Voxy/DH 在世界加载初期读到空缓存
            if (!FIRST_SYNC_DONE && !level.players().isEmpty()) {
                WeatherManager.sendBiomePacket(level, biomes, level.players());
                FIRST_SYNC_DONE = true;
            }
            // 之后每 100 tick (~5 秒) 同步
            if (level.getGameTime() % 100 == 0 && !level.players().isEmpty()) {
                WeatherManager.sendBiomePacket(level, biomes, level.players());
            }

        } catch (Exception e) {}
    }

    /**
     * TFC 温度 → ES 雪深 (0-100)。所有地方统一的转换公式。
     * 阈值从 {@link Config} 读取（支持运行时修改）。
     * <ul>
     *   <li>temp > snowAppearanceTemp → 0（零上无雪）</li>
     *   <li>temp ∈ [snowFullCoverageTemp, snowAppearanceTemp] → 线性渐变</li>
     *   <li>temp < snowFullCoverageTemp → 100（满雪）</li>
     * </ul>
     */
    /**
     * TFC 温度 → 雪深 (0-100)。无降水时使用正常温度阈值。
     */
    public static int tempToSnowDepth(float temp) {
        return tempToSnowDepth(temp, false);
    }

    /**
     * TFC 温度 → 雪深 (0-100)。
     *
     * @param temp         瞬时温度 (°C)
     * @param precipitating 是否正在降水（下雪）
     * @return 0-100 的雪深值
     *
     * <p>正常（无降水）：</p>
     * <ul>
     *   <li>temp &gt; snowAppearanceTemp → 0（无雪）</li>
     *   <li>temp &lt; snowFullCoverageTemp → 100（满雪）</li>
     * </ul>
     *
     * <p>降雪期间：</p>
     * <ul>
     *   <li>使用 snowSnowfallFullTemp（比 snowFullCoverageTemp 更暖）</li>
     *   <li>使得正在下雪时覆雪概率整体上移——所有位置同时获得更高的雪深</li>
     *   <li>加载区块和未加载区块（voxy/DH）看到相同的 boosted 曲线</li>
     *   <li>雪停后曲线回落，tickChunk 自动融化多余雪（depth ≤ seedMod 的方块脱雪）</li>
     * </ul>
     */
    public static int tempToSnowDepth(float temp, boolean precipitating) {
        float appear = Config.snowAppearanceTemp();
        float full = precipitating ? Config.snowSnowfallFullTemp() : Config.snowFullCoverageTemp();
        if (temp > appear) return 0;
        if (temp < full) return 100;
        // 线性插值: (appear - temp) / (appear - full) * 100
        return (int)((appear - temp) / (appear - full) * 100);
    }

    /**
     * 如果 TFC 气候对该维度活跃，返回 ClimateModel；否则返回 null。
     * 合并了常见的 guard 模式：{@code Climate.get() + supportsRain() + isWeatherEnabled()}。
     */
    @Nullable
    public static ClimateModel getModelIfActive(Level level) {
        ClimateModel model = Climate.get(level);
        if (!model.supportsRain()) return null;
        if (!WorldTracker.get(level).isWeatherEnabled()) return null;
        return model;
    }

    /**
     * TFC 瞬时温度 → ES 雪深 (0-100)。
     */
    public static int calcSnowDepth(ServerLevel level, BlockPos pos) {
        ClimateModel model = getModelIfActive(level);
        if (model == null) return 0;
        float temp = model.getInstantTemperature(level, pos);
        boolean precipitating = isPrecipitating(level, pos);
        return tempToSnowDepth(temp, precipitating);
    }

    /**
     * 检查 TFC 是否正在降水（客户端兼容版）。
     * 仅检查 rainIntensity > 0，不需要 per-position rainfall 或 WorldTracker。
     */
    public static boolean isPrecipitating(Level level) {
        ClimateModel model = Climate.get(level);
        if (!model.supportsRain()) return false;
        long calTicks = Calendars.get(level).getCalendarTicks();
        return model.getRain(calTicks) > 0;
    }

    /**
     * 检查 TFC 是否正在降水。
     */
    public static boolean isPrecipitating(ServerLevel level, BlockPos pos) {
        ClimateModel model = getModelIfActive(level);
        if (model == null) return false;

        long calTicks = Calendars.get(level).getCalendarTicks();
        float rainIntensity = model.getRain(calTicks);
        float rainfall = model.getInstantRainfall(level, pos);
        return WeatherHelpers.isPrecipitating(rainIntensity, rainfall);
    }
}
