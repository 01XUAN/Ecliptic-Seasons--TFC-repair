package me.xuan.estfc.weather;

import com.teamtea.eclipticseasons.api.constant.solar.Season;
import com.teamtea.eclipticseasons.api.constant.solar.SolarTerm;
import net.minecraft.util.Mth;

/**
 * 南半球季节/节气翻转 + 半球判定。
 */
public final class HemisphereSeasons {

    private HemisphereSeasons() {}

    /**
     * 由 z 坐标与半球尺度判定是否位于北半球。
     *
     * <p>1.20.1 的 TFC 没有半球概念（1.21 才有），这里按 1.21 的公式自行实现。</p>
     */
    public static boolean isNorthernHemisphere(int z, float hemisphereScale) {
        if (hemisphereScale == 0) return true;
        final int adjustedZ = z - (int) (hemisphereScale / 2);
        final int poleToPoleDistance = (int) (hemisphereScale * 2);
        final int normalizedZ = Mth.positiveModulo(adjustedZ, poleToPoleDistance * 2);
        return normalizedZ > poleToPoleDistance;
    }

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
