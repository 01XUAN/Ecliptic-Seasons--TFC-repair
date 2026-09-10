package me.xuan.estfc.mixin;

import com.teamtea.eclipticseasons.client.util.ClientCon;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import me.xuan.estfc.Config;
import me.xuan.estfc.client.RegionClimateCache;
import me.xuan.estfc.weather.ClimateQuery;
import me.xuan.estfc.weather.HemisphereSeasons;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端：南半球季节翻转。
 */
@Mixin(value = ClientCon.class, remap = false)
public abstract class ClientConMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private static void estfc$tickClient(Level clientLevel, CallbackInfo ci) {
        if (!MapChecker.isValidDimension(clientLevel)) return;

        RegionClimateCache.tick(clientLevel);

        // 临时诊断：每 200 tick 汇总一次客户端覆雪链路
        if (clientLevel.getGameTime() % 200 == 0) {
            org.slf4j.LoggerFactory.getLogger("estfc").info(
                "[estfc] clientTick findModelHits={} deltaPkts={} deltaPos={} initialPkts={}",
                me.xuan.estfc.client.DebugCounters.FIND_MODEL_HITS.getAndSet(0),
                me.xuan.estfc.client.DebugCounters.DELTA_PACKETS.getAndSet(0),
                me.xuan.estfc.client.DebugCounters.DELTA_POSITIONS.getAndSet(0),
                me.xuan.estfc.client.DebugCounters.INITIAL_PACKETS.getAndSet(0));
            org.slf4j.LoggerFactory.getLogger("estfc").info(
                "[estfc] clientTex swapped={} missingSwap={}",
                me.xuan.estfc.client.DebugCounters.TEX_SWAPPED.getAndSet(0),
                me.xuan.estfc.client.DebugCounters.TEX_MISSING.getAndSet(0));
        }

        if (ClimateQuery.getModelIfActive(clientLevel) == null) return;

        var player = Minecraft.getInstance().player;
        if (player == null) return;

        int z = player.blockPosition().getZ();
        if (HemisphereSeasons.isNorthernHemisphere(z, Config.hemisphereScale())) return;

        ClientCon.nowSeason = HemisphereSeasons.flipSeason(ClientCon.nowSeason);
        ClientCon.nowSolarTerm = HemisphereSeasons.flipSolarTerm(ClientCon.nowSolarTerm);
    }
}
