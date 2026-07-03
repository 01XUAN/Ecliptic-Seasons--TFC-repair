package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.api.misc.IBiomeWeatherProvider;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.dries007.tfc.util.tracker.WeatherHelpers;
import net.dries007.tfc.util.tracker.WorldTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

/**
 * 方案B: 完全由 TFC 气候驱动 ES 积雪系统。
 *
 * <h3>核心改动</h3>
 * <ul>
 *   <li>禁用 ES {@code agentAdvanceWeatherCycle} → ES 天气循环不再自主运行</li>
 *   <li>新增 {@code getSnowStatus(4参数)} 注入 — 这是之前遗漏的关键方法</li>
 *   <li>修复 {@code getPrecipitationAt} — 加入 TFC 降水判断</li>
 *   <li>新增 {@code tickWeatherCycle} — 由 TFC 天气循环 TAIL 调用，驱动群系积雪</li>
 * </ul>
 *
 * <h3>雪状态决策</h3>
 * <ul>
 *   <li>temp > 0              → SNOW_MELT（零上必融）</li>
 *   <li>temp ≤ 0 + 正在降水   → SNOW（积）</li>
 *   <li>temp ≤ 0 + 无降水     → NONE（不动，零下绝不融雪！）</li>
 * </ul>
 */
@Mixin(value = WeatherManager.class, remap = false)
public abstract class WeatherManagerMixin {

    @Unique
    private static final Logger ESTFC_LOG = LoggerFactory.getLogger("estfc");
    @Unique
    private static boolean LOGGED = false;

    // ==================== 禁用 ES 自主天气循环 ====================

    /**
     * 直接取消 {@code agentAdvanceWeatherCycle}。
     * ES 不再对 ServerLevelData (rainTime/clearTime) 做任何修改。
     * 群系雪深由 {@link #estfc$tickWeatherCycle(ServerLevel)} 驱动。
     */
    @Inject(method = "agentAdvanceWeatherCycle", at = @At("HEAD"), cancellable = true)
    private static void estfc$cancelAgentWeatherCycle(ServerLevel level, RandomSource random,
                                                       CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    // ==================== 群系降水过滤器 ====================

    /**
     * 所有群系都可能通过 TFC 有降水，不让 ES 过滤掉。
     */
    @Inject(method = "hasNonePrecipitation", at = @At("HEAD"), cancellable = true)
    private static void estfc$hasNonePrecipitation(Biome biome, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    // ==================== 雨/雪判断 ====================

    /**
     * 直接委托给 TFC 的降水判断。
     */
    @Inject(method = "getRainOrSnow", at = @At("HEAD"), cancellable = true)
    private static void estfc$getRainOrSnow(Level level, Biome biome, BlockPos pos,
                                             CallbackInfoReturnable<Biome.Precipitation> cir) {
        if (level == null) return;
        try {
            Biome.Precipitation tfcPrecip = WeatherHelpers.getPrecipitationAt(level, pos, Biome.Precipitation.NONE);
            cir.setReturnValue(tfcPrecip);
        } catch (Exception e) {}
    }

    // ==================== 雪状态 — 3参数版 ====================

    /**
     * 3 参数版 — 被部分旧代码调用。与 4 参数版共用实现。
     */
    @Inject(method = "getSnowStatus", at = @At("HEAD"), cancellable = true)
    private static void estfc$getSnowStatus3(
        ServerLevel level, Holder<Biome> biome, BlockPos pos,
        CallbackInfoReturnable<WeatherManager.SnowRenderStatus> cir) {
        estfc$getSnowStatusImpl(level, pos, cir);
    }

    // ==================== 雪状态 — 4参数版（关键修复！） ====================

    /**
     * 4 参数版 — {@link com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper#tickChunk}
     * 直接调用此版本，之前的 mixin 遗漏了这个重载，导致 per-block 雪判断走 ES 原生逻辑。
     */
    @Inject(method = "getSnowStatus", at = @At("HEAD"), cancellable = true)
    private static void estfc$getSnowStatus4(
        ServerLevel level, Holder<Biome> biome, BlockPos pos, boolean rain,
        CallbackInfoReturnable<WeatherManager.SnowRenderStatus> cir) {
        estfc$getSnowStatusImpl(level, pos, cir);
    }

    // ==================== 降水类型（修复：加入TFC降水判断） ====================

    /**
     * 之前的实现只看温度（depth > 0 → SNOW），无论是否真的在下雪。
     * 修复：先检查 TFC 是否正在降水，不再降水时返回 NONE。
     */
    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private static void estfc$getPrecipitationAt(
        Biome biome, BlockPos pos,
        CallbackInfoReturnable<Biome.Precipitation> cir) {

        try {
            Level level = WeatherManager.fetchLevelIfNull(null, biome);
            if (level == null) return;

            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return;
            if (!WorldTracker.get(level).isWeatherEnabled()) return;

            boolean precipitating = estfc$isPrecipitating((ServerLevel) level, pos);
            if (!precipitating) {
                cir.setReturnValue(Biome.Precipitation.NONE);
                return;
            }

            float temp = model.getInstantTemperature(level, pos);
            if (temp > 0) {
                cir.setReturnValue(Biome.Precipitation.RAIN);
            } else {
                cir.setReturnValue(Biome.Precipitation.SNOW);
            }
        } catch (Exception e) {}
    }

    // ==================== 核心：由 TFC 天气循环驱动的群系雪深更新 ====================

    /**
     * 每 tick 处理一个群系的雪深。由 {@code WeatherHelpersMixin} 在
     * TFC 的 {@code advanceWeatherCycle} TAIL 调用。
     *
     * <p>复用 ES 的 {@code IBiomeWeatherProvider} 群系轮转计数器，
     * 使用 TFC 气候数据决定雪深增减。</p>
     */
    @Unique
    public static void estfc$tickWeatherCycle(ServerLevel level) {
        try {
            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return;

            WorldTracker tracker = WorldTracker.get(level);
            if (!tracker.isWeatherEnabled()) return;

            IBiomeWeatherProvider ip = level instanceof IBiomeWeatherProvider ips ? ips : null;
            if (ip == null) return;

            ArrayList<WeatherManager.BiomeWeather> biomes = ip.es$get();
            if (biomes == null || biomes.isEmpty()) return;

            int index = ip.es$getTickBiome();
            WeatherManager.BiomeWeather biomeWeather = biomes.get(index);
            if (biomeWeather == null || biomeWeather.biomeHolder == null) return;

            // 用 Y=64 代表群系级温度判断
            BlockPos pos = new BlockPos(0, 64, 0);
            int depth = estfc$calcSnowDepth(level, pos);
            boolean precip = estfc$isPrecipitating(level, pos);

            if (!LOGGED) {
                LOGGED = true;
                ESTFC_LOG.info("[estfc] tickWeatherCycle active: biome={} depth={} precip={}",
                    biomeWeather.biomeHolder.getKey().location(), depth, precip);
            }

            // 更新群系级雪深 (rate = 1, 群系轮转提供自然的渐变速率)
            if (depth > 0 && precip) {
                // 零下 + 降水 → 积雪积累
                biomeWeather.setSnowDepth(Math.min(100, (biomeWeather.getSnowDepth() & 0xFF) + 1));
            } else if (depth == 0) {
                // 零上 → 融雪
                biomeWeather.setSnowDepth(Math.max(0, (biomeWeather.getSnowDepth() & 0xFF) - 1));
            }
            // 零下 + 无降水 → 不动（雪不会在零下消失！）

            // 每 100 tick (~5 秒) 同步 BiomeWeatherMessage 到客户端
            if (level.getGameTime() % 100 == 0 && !level.players().isEmpty()) {
                WeatherManager.sendBiomePacket(level, biomes, level.players());
            }
        } catch (Exception e) {}
    }

    // ==================== 共享实现 ====================

    /**
     * getSnowStatus 的统一实现。所有重载版本都走这里。
     */
    @Unique
    private static void estfc$getSnowStatusImpl(
        ServerLevel level, BlockPos pos,
        CallbackInfoReturnable<WeatherManager.SnowRenderStatus> cir) {

        try {
            ClimateModel model = Climate.get(level);
            if (!model.supportsRain()) return;
            if (!WorldTracker.get(level).isWeatherEnabled()) return;

            float temp = model.getInstantTemperature(level, pos);
            boolean precipitating = estfc$isPrecipitating(level, pos);

            if (temp > 0) {
                // 零上 → 必定融雪
                cir.setReturnValue(WeatherManager.SnowRenderStatus.SNOW_MELT);
            } else if (precipitating) {
                // 零下 + 正在降水 → 积雪
                cir.setReturnValue(WeatherManager.SnowRenderStatus.SNOW);
            } else {
                // 零下 + 无降水 → 不动（雪保持）
                cir.setReturnValue(WeatherManager.SnowRenderStatus.NONE);
            }
        } catch (Exception e) {}
    }

    // ==================== 核心计算 ====================

    /**
     * TFC 瞬时温度 → ES 雪深 (0-100)。
     * <ul>
     *   <li>temp < -10°C → 100（极寒满雪）</li>
     *   <li>temp ∈ [-10, 0] → 20 + |temp| * 8（线性 20~100）</li>
     *   <li>temp > 0°C → 0（零上无雪）</li>
     * </ul>
     */
    @Unique
    private static int estfc$calcSnowDepth(ServerLevel level, BlockPos pos) {
        ClimateModel model = Climate.get(level);
        if (!model.supportsRain()) return 0;

        WorldTracker tracker = WorldTracker.get(level);
        if (!tracker.isWeatherEnabled()) return 0;

        float temp = model.getInstantTemperature(level, pos);

        if (temp < -10) return 100;
        if (temp <= 0)  return 20 + (int)(Math.abs(temp) * 8);
        return 0;
    }

    /**
     * 检查 TFC 是否正在降水（结合全局 rain 信号 + 本地 rainfall）。
     */
    @Unique
    private static boolean estfc$isPrecipitating(ServerLevel level, BlockPos pos) {
        ClimateModel model = Climate.get(level);
        if (!model.supportsRain()) return false;

        WorldTracker tracker = WorldTracker.get(level);
        if (!tracker.isWeatherEnabled()) return false;

        long calTicks = Calendars.get(level).getCalendarTicks();
        float rainIntensity = model.getRain(calTicks);
        float rainfall = model.getInstantRainfall(level, pos);
        return WeatherHelpers.isPrecipitating(rainIntensity, rainfall);
    }
}
