package me.xuan.estfc;

import com.mojang.logging.LogUtils;
import me.xuan.estfc.client.DhSnowService;
import me.xuan.estfc.client.gui.EstfcConfigScreen;
import me.xuan.estfc.net.RegionClimatePayloads;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.slf4j.Logger;

/**
 * Ecliptic Seasons ↔ TerraFirmaCraft 桥接
 */
@Mod(EclipticSeasonsTFCrepair.MODID)
public class EclipticSeasonsTFCrepair {
    public static final String MODID = "estfc";

    public EclipticSeasonsTFCrepair(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(Config::updateConfig);
        modEventBus.addListener(RegionClimatePayloads::register);
        // 区域气候点服务端计算限流（ServerTickEvent.Post 仅服务端触发）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(RegionClimatePayloads::onServerTick);
        // 服务器停止时清空区域气候点缓存（单机换世界：旧世界数据不能复用）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            net.neoforged.neoforge.event.server.ServerStoppedEvent.class,
            e -> RegionClimatePayloads.clearServerCaches());
        // DH 覆雪事件绑定（FMLClientSetupEvent 仅客户端触发，早于世界加载）。
        // DH 是可选模组：未安装时不能触碰 DhSnowService（其类引用 DH API，
        // 缺失会 NoClassDefFoundError）。
        modEventBus.addListener((FMLClientSetupEvent e) -> {
            if (ModList.get().isLoaded("distanthorizons")) {
                DhSnowService.init();
            }
        });
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, EstfcConfigScreen::new);
    }
}
