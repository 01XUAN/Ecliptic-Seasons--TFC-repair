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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在客户端 tick 更新季节后，根据 TFC 南北半球翻转南半球的季节/节气。
 *
 * <p>ES 的季节系统（{@code ClientCon.nowSeason} / {@code nowSolarTerm}）
 * 是全局的，假设全世界同季节。但 TFC 通过 Z 坐标区分南北半球，
 * 南半球季节与北半球相反。</p>
 *
 * <p>此 Mixin 在 {@code ClientCon.tick()} 尾部注入：
 * <ul>
 *   <li>北半球 → 不修改（ES 默认即为北半球）</li>
 *   <li>南半球 → 翻转 Season（SPRING↔AUTUMN, SUMMER↔WINTER）</li>
 *   <li>南半球 → 翻转 SolarTerm（ordinal + 12，mod 24）</li>
 * </ul>
 *
 * 影响范围：萤火虫/蝴蝶/大雁粒子 + 蟋蟀等环境音效的季节判断。
 */
@Mixin(value = ClientCon.class, remap = false)
public abstract class ClientConMixin {

    @Unique
    private static final Logger ESTFC_LOG = LoggerFactory.getLogger("estfc");
    @Unique
    private static boolean LOGGED = false;

    @Inject(method = "tick", at = @At("TAIL"))
    private static void estfc$flipSeasonForSouthernHemisphere(Level clientLevel, CallbackInfo ci) {
        if (!MapChecker.isValidDimension(clientLevel)) return;

        try {
            ClimateModel model = Climate.get(clientLevel);
            if (!model.supportsRain()) return;

            var player = Minecraft.getInstance().player;
            if (player == null) return;

            int z = player.blockPosition().getZ();
            boolean isNorthern = SolarCalculator.getInNorthernHemisphere(z, model.hemisphereScale());

            if (isNorthern) return; // 北半球不修改

            // 南半球 → 翻转季节
            ClientCon.nowSeason = estfc$flipSeason(ClientCon.nowSeason);
            ClientCon.nowSolarTerm = estfc$flipSolarTerm(ClientCon.nowSolarTerm);

            if (!LOGGED) {
                LOGGED = true;
                ESTFC_LOG.info("[estfc] Southern hemisphere detected at z={}: season={} term={}",
                    z, ClientCon.nowSeason, ClientCon.nowSolarTerm);
            }
        } catch (Exception e) {
            // TFC 不可用时静默跳过
        }
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
        // 24 节气 ordinal: 0(BEGINNING_OF_SPRING) ~ 23(GREATER_COLD), 24(NONE)
        // 南半球翻转: ordinal + 12 (mod 24)
        int idx = (term.ordinal() + 12) % 24;
        return SolarTerm.values()[idx];
    }
}
