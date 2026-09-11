package me.xuan.estfc;

import me.xuan.estfc.client.DhSnowService;
import me.xuan.estfc.net.RegionClimatePayloads;
import me.xuan.estfc.weather.ChunkSnowScheduler;
import me.xuan.estfc.weather.ChunkSnowTicker;
import me.xuan.estfc.weather.WeatherCycleService;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ecliptic Seasons ↔ TerraFirmaCraft 桥接（Forge 1.20.1）。
 */
@Mod(EclipticSeasonsTFCrepair.MODID)
public class EclipticSeasonsTFCrepair {

    public static final String MODID = "estfc";
    public static final Logger LOGGER = LoggerFactory.getLogger("estfc");

    public EclipticSeasonsTFCrepair() {
        ModLoadingContext context = ModLoadingContext.get();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modBus.addListener(Config::updateConfig);
        modBus.addListener((FMLCommonSetupEvent e) -> RegionClimatePayloads.register());

        // 区域气候点：服务端 tick 入队提交 + 服务器停止清缓存
        MinecraftForge.EVENT_BUS.addListener(RegionClimatePayloads::onServerTick);
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            RegionClimatePayloads.clearServerCaches();
            ChunkSnowScheduler.clear();
        });

        // 群系雪深主循环：1.20.1 的 TFC 在 LevelTickEvent.START 推进天气循环，
        // estfc 在 END 相位驱动（等价于 1.21.1 注入 WeatherHelpers.advanceWeatherCycle RETURN）。
        MinecraftForge.EVENT_BUS.addListener((TickEvent.LevelTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END && e.level instanceof ServerLevel serverLevel) {
                ChunkSnowScheduler.tick(serverLevel);
                WeatherCycleService.tickWeatherCycle(serverLevel);
                ChunkSnowTicker.tickLevel(serverLevel);
            }
        });

        // DH 覆雪事件绑定（FMLClientSetupEvent 仅客户端触发，早于世界加载）。
        // DH 是可选模组：未安装时不能触碰 DhSnowService（其类引用 DH API，
        // 缺失会 NoClassDefFoundError）。
        modBus.addListener((FMLClientSetupEvent e) -> {
            if (ModList.get().isLoaded("distanthorizons")) {
                DhSnowService.init();
            }
        });

        // 1.20.1 使用 Forge 自带的配置界面（Mods 列表 → Config），
        // 不注册自定义界面（1.20.1 缺 AbstractContainerWidget，自定义滚动布局需另写）。
    }
}
