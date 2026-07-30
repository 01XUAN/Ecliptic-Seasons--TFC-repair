package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.api.constant.solar.SolarTerm;
import com.teamtea.eclipticseasons.common.core.solar.SolarDataManager;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.calendar.ICalendar;
import net.dries007.tfc.util.calendar.Month;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.ref.WeakReference;

/**
 * 覆盖 {@link SolarDataManager#getSolarTermIndex()}，
 * 从 TFC 日历派生节气索引，使节气模组的节气与群峦模组的月份对齐。
 */
@Mixin(value = SolarDataManager.class, remap = false)
public abstract class SolarDataManagerMixin {

    @Shadow
    protected WeakReference<Level> levelWeakReference;

    /**
     * 在 getSolarTermIndex() 头部注入，使用 TFC 日历计算节气。
     * 映射：每月 = 2 个节气，月内前半段为第一个节气，后半段为第二个节气。
     * 公式：solarTermIndex = ((month.ordinal() + 11) * 2 + (fraction >= 0.5 ? 1 : 0)) % 24
     */
    @Inject(method = "getSolarTermIndex", at = @At("HEAD"), cancellable = true)
    private void estfc$getSolarTermIndex(CallbackInfoReturnable<Integer> cir) {
        Level level = levelWeakReference != null ? levelWeakReference.get() : null;
        if (level == null) return;

        try {
            ICalendar calendar = Calendars.get(level);
            long calendarTicks = calendar.getCalendarTicks();
            int daysInMonth = calendar.getCalendarDaysInMonth();

            Month month = ICalendar.getMonthOfYear(calendarTicks, daysInMonth);
            float fraction = ICalendar.getFractionOfMonth(calendarTicks, daysInMonth);

            // 每月 2 个节气，前半月为第一个，后半月为第二个
            int subIndex = fraction >= 0.5f ? 1 : 0;
            int solarTermIndex = ((month.ordinal() + 11) * 2 + subIndex) % 24;

            cir.setReturnValue(solarTermIndex);
        } catch (Exception e) {
            // TFC 日历不可用时，回退到 ES 原有逻辑（不取消注入）
        }
    }
}
