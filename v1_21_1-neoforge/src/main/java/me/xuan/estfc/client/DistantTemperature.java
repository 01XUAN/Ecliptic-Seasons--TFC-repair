package me.xuan.estfc.client;

import me.xuan.estfc.mixin.OverworldClimateModelMixin;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.calendar.ICalendar;
import net.dries007.tfc.util.calendar.Month;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.OverworldClimateModel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * 远景温度：avgSeaLevelTemp + 月温 + 日温 ± 海拔。
 * 月温/日温/海拔复用 TFC 原实现（@Invoker）；日温随客户端日历变化，
 * DH 的烘焙色要等 LOD 重建才刷新，voxy 由 VoxyCompact 定时重渲染。
 * 海拔 y 取数据点高度（voxy=真实地表）或区域表面高度（DH，128 格平滑场）。
 * 区域数据未同步时返回 null。
 */
public final class DistantTemperature {

    private record BaseTemp(OverworldClimateModel model, float avgSea, float month, float daily) {}

    private DistantTemperature() {}

    /** 含海拔（精确 y，voxy 路径）。 */
    @Nullable
    public static Float distantTemperature(Level level, RegionClimateCache.Sample sample, int y) {
        BaseTemp base = baseTemperature(level, sample);
        return base == null ? null : adjust(base, y);
    }

    /** avgSeaLevelTemp + 月温 + 日温（都是 (x,z,日历) 纯函数）。 */
    @Nullable
    private static BaseTemp baseTemperature(Level level, RegionClimateCache.Sample sample) {
        if (!(Climate.get(level) instanceof OverworldClimateModel model)) return null;

        ICalendar calendar = Calendars.get(level);
        long calTicks = calendar.getCalendarTicks();
        int daysInMonth = calendar.getCalendarDaysInMonth();

        Month month = ICalendar.getMonthOfYear(calTicks, daysInMonth);
        float delta = ICalendar.getFractionOfMonth(calTicks, daysInMonth);
        float monthFactor = Mth.lerp(delta, month.getTemperatureModifier(), month.next().getTemperatureModifier());

        float avgSea = sample.temp();
        if (Float.isNaN(avgSea)) return null;

        OverworldClimateModelMixin accessor = (OverworldClimateModelMixin) model;
        float monthTemperature = accessor.estfc$calculateMonthlyTemperature(sample.z(), monthFactor);
        float dailyTemperature = accessor.estfc$calculateDailyTemperature(calTicks, daysInMonth, sample.z());
        return new BaseTemp(model, avgSea, monthTemperature, dailyTemperature);
    }

    /** 复刻 getInstantTemperature 海拔分支（含日温）。 */
    private static float adjust(BaseTemp base, int y) {
        return ((OverworldClimateModelMixin) base.model())
            .estfc$adjustTemperatureByElevation(y, base.avgSea(), base.month(), base.daily());
    }
}
