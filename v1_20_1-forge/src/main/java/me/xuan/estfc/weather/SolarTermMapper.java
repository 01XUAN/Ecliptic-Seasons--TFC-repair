package me.xuan.estfc.weather;

import net.dries007.tfc.util.calendar.Month;

/**
 * TFC 月份 → ES 节气索引映射。
 * 每月 = 2 个节气，月内前半段为第一个节气，后半段为第二个节气。
 */
public final class SolarTermMapper {

    private SolarTermMapper() {}

    public static final int TERMS_PER_YEAR = 24;
    public static final int TERMS_PER_MONTH = 2;

    /** {@code ((month.ordinal() + 11) * 2 + (fractionOfMonth >= 0.5f ? 1 : 0)) % 24} */
    public static int solarTermIndex(Month month, float fractionOfMonth) {
        int subIndex = fractionOfMonth >= 0.5f ? 1 : 0;
        return ((month.ordinal() + 11) * TERMS_PER_MONTH + subIndex) % TERMS_PER_YEAR;
    }
}
