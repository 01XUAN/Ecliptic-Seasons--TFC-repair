package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.api.constant.solar.Season;
import com.teamtea.eclipticseasons.api.constant.solar.SolarTerm;
import com.teamtea.eclipticseasons.client.util.ClientCon;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import net.dries007.tfc.client.overworld.SolarCalculator;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.util.climate.ClimateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在客户端 tick 更新季节后，根据 TFC 南北半球翻转南半球的季节/节气。
 * 影响：萤火虫/蝴蝶/大雁粒子 + 蟋蟀等环境音效的季节判断。
 */
@Mixin(value = ClientCon.class, remap = false)
public abstract class ClientConMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private static void estfc$flipSeasonForSouthernHemisphere(Level clientLevel, CallbackInfo ci) {
        if (!MapChecker.isValidDimension(clientLevel)) return;

        ClimateModel model = Climate.get(clientLevel);
        if (!model.supportsRain()) return;

        var player = Minecraft.getInstance().player;
        if (player == null) return;

        // ===== 南半球季节翻转 =====
        int z = player.blockPosition().getZ();
        boolean isNorthern = SolarCalculator.getInNorthernHemisphere(z, model.hemisphereScale());

        if (isNorthern) return;

        ClientCon.nowSeason = estfc$flipSeason(ClientCon.nowSeason);
        ClientCon.nowSolarTerm = estfc$flipSolarTerm(ClientCon.nowSolarTerm);
    }

    @Unique
    private static Season estfc$flipSeason(Season season) {
        return switch (season) {
            case SPRING -> Season.AUTUMN;
            case SUMMER -> Season.WINTER;
            case AUTUMN -> Season.SPRING;
            case WINTER -> Season.SUMMER;
            case NONE  -> Season.NONE;
        };
    }

    @Unique
    private static SolarTerm estfc$flipSolarTerm(SolarTerm term) {
        if (term == SolarTerm.NONE) return SolarTerm.NONE;
        int idx = (term.ordinal() + 12) % 24;
        return SolarTerm.values()[idx];
    }
}
