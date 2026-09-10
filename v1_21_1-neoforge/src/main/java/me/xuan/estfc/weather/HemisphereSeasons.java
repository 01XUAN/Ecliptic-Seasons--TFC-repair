package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.api.constant.solar.Season;
import com.teamtea.eclipticseasons.api.constant.solar.SolarTerm;

/**
 * 南半球季节/节气翻转。
 */
public final class HemisphereSeasons {

    private HemisphereSeasons() {}

    public static Season flipSeason(Season season) {
        return switch (season) {
            case SPRING -> Season.AUTUMN;
            case SUMMER -> Season.WINTER;
            case AUTUMN -> Season.SPRING;
            case WINTER -> Season.SUMMER;
            case NONE  -> Season.NONE;
        };
    }

    /** NONE 位于 ordinal 24，必须短路；取模只作用于 0..23。 */
    public static SolarTerm flipSolarTerm(SolarTerm term) {
        if (term == SolarTerm.NONE) return SolarTerm.NONE;
        int idx = (term.ordinal() + 12) % 24;
        return SolarTerm.values()[idx];
    }
}
