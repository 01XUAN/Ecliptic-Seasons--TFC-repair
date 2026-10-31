package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import me.xuan.estfc.Config;
import me.xuan.estfc.weather.BiomeCoverageCache;
import me.xuan.estfc.weather.ClimateQuery;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 完全由 TFC 气候驱动 ES 积雪系统：禁用 ES 自主天气循环，
 * 用 TFC per-block 温度替换 biomes 级缓存阈值做雪/雨/融雪决策。
 */
@Mixin(value = WeatherManager.class, remap = false)
public abstract class WeatherManagerMixin {

    // 禁用 ES 自主天气循环

    /**
     * 直接取消 {@code agentAdvanceWeatherCycle}。
     * ES 不再对 ServerLevelData (rainTime/clearTime) 做任何修改。
     * 群系雪深由 {@link me.xuan.estfc.weather.WeatherCycleService#tickWeatherCycle(ServerLevel)} 驱动。
     */
    @Inject(method = "agentAdvanceWeatherCycle", at = @At("HEAD"), cancellable = true)
    private static void estfc$cancelAgentWeatherCycle(ServerLevel level, RandomSource random,
                                                       CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    // 群系降水过滤

    /**
     * 所有群系都可能通过 TFC 有降水，不让 ES 过滤掉。
     */
    @Inject(method = "hasNonePrecipitation", at = @At("HEAD"), cancellable = true)
    private static void estfc$hasNonePrecipitation(Biome biome, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    // 雨/雪判断

    /**
     * 直接委托给 TFC 的降水判断。
     */
    @Inject(method = "getRainOrSnow", at = @At("HEAD"), cancellable = true)
    private static void estfc$getRainOrSnow(Level level, Biome biome, BlockPos pos,
                                             CallbackInfoReturnable<Biome.Precipitation> cir) {
        if (level == null) return;
        try {
            Biome.Precipitation tfcPrecip = Climate.getPrecipitation(level, pos);
            cir.setReturnValue(tfcPrecip);
        } catch (Exception e) {}
    }

    // 雪状态

    /**
     * {@link com.teamtea.eclipticseasons.common.core.snow.SnowyStatusKeeper#tickChunk}
     */
    @Inject(
        method = "getSnowStatus(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/biome/Biome;Lnet/minecraft/core/BlockPos;Z)Lcom/teamtea/eclipticseasons/common/core/biome/WeatherManager$SnowRenderStatus;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void estfc$getSnowStatus4(
        ServerLevel level, Biome biome, BlockPos pos, boolean rain,
        CallbackInfoReturnable<WeatherManager.SnowRenderStatus> cir) {
        estfc$getSnowStatusImpl(level, biome, pos, cir);
    }

    // 降水类型

    /**
     * 先检查 TFC 是否正在降水，不再降水时返回 NONE。
     */
    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private static void estfc$getPrecipitationAt(
        Biome biome, BlockPos pos,
        CallbackInfoReturnable<Biome.Precipitation> cir) {

        try {
            Level level = WeatherManager.fetchLevelIfNull(null);
            if (level == null) return;

            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) return;

            boolean precipitating = ClimateQuery.isPrecipitating((ServerLevel) level, pos);
            if (!precipitating) {
                cir.setReturnValue(Biome.Precipitation.NONE);
                return;
            }

            float temp = ClimateQuery.instantTemperature(level, pos);
            if (temp > Config.snowMeltTemp()) {
                cir.setReturnValue(Biome.Precipitation.RAIN);
            } else {
                cir.setReturnValue(Biome.Precipitation.SNOW);
            }
        } catch (Exception e) {}
    }

    // 共享实现

    @Unique
    private static void estfc$getSnowStatusImpl(
        ServerLevel level, Biome biome, BlockPos pos,
        CallbackInfoReturnable<WeatherManager.SnowRenderStatus> cir) {

        // ES 的 WeatherManager.updateSnowOrMelt 用 BlockPos.ZERO 作哨兵，直接加减
        // BiomeWeather.snowDepth；estfc 的 WeatherCycleService 也在写同一个值，
        // 两者交替会把群系雪深在 100 和覆盖率之间来回拉。雪深只允许 estfc 更新。
        if (BlockPos.ZERO.equals(pos)) {
            cir.setReturnValue(WeatherManager.SnowRenderStatus.NONE);
            return;
        }

        try {
            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) return;

            float temp = ClimateQuery.instantTemperature(level, pos);
            float meltTemp = Config.snowMeltTemp();

            if (temp > meltTemp) {
                // 高于融雪温度 → 融雪。
                cir.setReturnValue(WeatherManager.SnowRenderStatus.SNOW_MELT);
            } else if (BiomeCoverageCache.isClimateOnly(level, biome)) {
                // 初始气候种子：奇偶 tick 交替加/清，双向逼近 C
                cir.setReturnValue((level.getGameTime() & 1L) == 0L
                    ? WeatherManager.SnowRenderStatus.SNOW
                    : WeatherManager.SnowRenderStatus.SNOW_MELT);
            } else {
                // 低温无降水：物理雪/冰不要继续生成；可视化补齐在 SnowyStatusKeeperMixin
                // 内单独把 NONE 包成 SNOW，只改雪标志，不影响物理雪。
                cir.setReturnValue(WeatherManager.SnowRenderStatus.NONE);
            }
        } catch (Exception e) {}
    }

}
