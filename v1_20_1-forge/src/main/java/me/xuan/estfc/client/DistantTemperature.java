package me.xuan.estfc.client;

import me.xuan.estfc.mixin.OverworldClimateModelMixin;
import me.xuan.estfc.weather.ClimateQuery;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.calendar.ICalendar;
import net.dries007.tfc.util.calendar.Month;

import net.dries007.tfc.util.climate.OverworldClimateModel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * 远景温度：avgSeaLevelTemp + 月温 ± 海拔，无日温（跨日跳变，LOD 重建才刷新）。
 * 月温/海拔复用 TFC 原实现（@Invoker）；海拔 y 取数据点高度（voxy=真实地表）
 * 或区域表面高度（DH，128 格平滑场）。区域数据未同步时返回 null。
 */
public final class DistantTemperature {

    private record BaseTemp(OverworldClimateModel model, float avgSea, float month) {}

    private DistantTemperature() {}

    /** 含海拔（精确 y，voxy 路径）。 */
    @Nullable
    public static Float distantTemperature(Level level, RegionClimateCache.Sample sample, int y) {
        BaseTemp base = baseTemperature(level, sample.x(), sample.z());
        return base == null ? null : adjust(base, y);
    }

    /**
     * DH 路径：海拔取<b>区域表面高度</b>（128 格场，服务端 {@code getBaseHeight} 同步）。
     *
     * <p>DH 事件给的 y 不是地面高度（粗级别 = 列内最高点、细级别 = 顶部 merged run 的底部），
     * 逐数据点用它当海拔会按级别分档 → 远景网格。区域高度级别无关，与 1.21.1 一致。</p>
     */
    @Nullable
    public static Float distantTemperatureRegional(Level level, RegionClimateCache.Sample sample) {
        BaseTemp base = baseTemperature(level, sample.x(), sample.z());
        return base == null ? null : adjust(base, (int) sample.height());
    }

    /** avgSeaLevelTemp + 月温（都是 (x,z,日历) 纯函数）。 */
    @Nullable
    private static BaseTemp baseTemperature(Level level, int x, int z) {
        OverworldClimateModel model = ClimateQuery.getOverworldModelIfActive(level);
        if (model == null) return null;

        ICalendar calendar = Calendars.get(level);
        long calTicks = calendar.getCalendarTicks();
        int daysInMonth = calendar.getCalendarDaysInMonth();

        Month month = ICalendar.getMonthOfYear(calTicks, daysInMonth);
        float delta = ICalendar.getFractionOfMonth(calTicks, daysInMonth);
        float monthFactor = Mth.lerp(delta, month.getTemperatureModifier(), month.next().getTemperatureModifier());

        float avgSea = RegionClimateCache.avgSeaLevelTemp(x, z);
        if (Float.isNaN(avgSea)) return null;

        float monthTemperature = ((OverworldClimateModelMixin) model).estfc$calculateMonthlyTemperature(z, monthFactor);
        return new BaseTemp(model, avgSea, monthTemperature);
    }

    /** 复刻 getInstantTemperature 海拔分支（日温=0）。 */
    private static float adjust(BaseTemp base, int y) {
        return ((OverworldClimateModelMixin) base.model())
            .estfc$adjustTemperatureByElevation(y, base.avgSea(), base.month(), 0f);
    }
}
