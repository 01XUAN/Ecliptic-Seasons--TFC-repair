package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import me.xuan.estfc.Config;
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
        estfc$getSnowStatusImpl(level, pos, cir);
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
        ServerLevel level, BlockPos pos,
        CallbackInfoReturnable<WeatherManager.SnowRenderStatus> cir) {

        try {
            ClimateModel model = ClimateQuery.getModelIfActive(level);
            if (model == null) return;

            float temp = ClimateQuery.instantTemperature(level, pos);
            boolean precipitating = ClimateQuery.isPrecipitating(level, pos);
            float meltTemp = Config.snowMeltTemp();

            if (temp > meltTemp) {
                // 高于融雪温度 → 融雪。
                cir.setReturnValue(WeatherManager.SnowRenderStatus.SNOW_MELT);
            } else if (precipitating) {
                // 低于融雪温度 + 正在降水 → 积雪（只在降雪期间添加）
                cir.setReturnValue(WeatherManager.SnowRenderStatus.SNOW);
            } else {
                // 低于融雪温度 + 无降水 → 不动（保持现有雪，不增不减）
                cir.setReturnValue(WeatherManager.SnowRenderStatus.NONE);
            }
        } catch (Exception e) {}
    }

}
