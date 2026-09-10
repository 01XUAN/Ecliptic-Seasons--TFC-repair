package me.xuan.estfc.mixin;

import net.dries007.tfc.util.climate.OverworldClimateModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 客户端 OverworldClimateModel 内部量访问（temperatureScale 由 TFC STREAM_CODEC 同步；
 * 月温/海拔公式以 @Invoker 复用 TFC 原实现，避免复刻漂移）。
 */
@Mixin(value = OverworldClimateModel.class, remap = false)
public interface OverworldClimateModelMixin {

    @Accessor("temperatureScale")
    float estfc$temperatureScale();

    @Invoker("calculateMonthlyTemperature")
    float estfc$calculateMonthlyTemperature(int z, float monthTemperatureModifier);

    @Invoker("adjustTemperatureByElevation")
    float estfc$adjustTemperatureByElevation(int y, float averageTemperature, float monthTemperature, float dailyTemperature);
}
