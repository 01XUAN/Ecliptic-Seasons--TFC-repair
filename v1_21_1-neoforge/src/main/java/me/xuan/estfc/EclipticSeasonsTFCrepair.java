package me.xuan.estfc;

import com.mojang.logging.LogUtils;
import me.xuan.estfc.client.DhSnowService;
import me.xuan.estfc.client.gui.EstfcConfigScreen;
import me.xuan.estfc.command.EstfcDiagnosticsCommand;
import me.xuan.estfc.net.RegionClimatePayloads;
import me.xuan.estfc.weather.BiomeCoverageCache;
import me.xuan.estfc.weather.BiomeSampleCache;
import me.xuan.estfc.weather.BiomeSnowSeed;
import me.xuan.estfc.weather.ChunkSnowRefresh;
import me.xuan.estfc.weather.WeatherCycleService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import com.teamtea.eclipticseasons.common.core.map.ChunkInfoMap;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.level.ChunkEvent;
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
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(EstfcDiagnosticsCommand::register);
        // 区块重新加载时强制按当前 C 重算，避免沿用卸载前的旧雪标志（ES 的 force update 可能因 biomeHolder 缺失跳过）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((ChunkEvent.Load e) -> {
            if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof LevelChunk chunk) {
                ChunkInfoMap map = MapChecker.getChunkInfoMapOrCreate(level, chunk.getPos());
                if (map != null) ChunkSnowRefresh.refreshChunk(level, chunk, map, true);
            }
        });
        // 服务器停止时清空缓存（单机换世界：旧世界数据不能复用）
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            net.neoforged.neoforge.event.server.ServerStoppedEvent.class,
            e -> {
                RegionClimatePayloads.clearServerCaches();
                BiomeCoverageCache.clear();
                BiomeSampleCache.clear();
                BiomeSnowSeed.clear();
                WeatherCycleService.clear();
            });
        // DH 覆雪事件绑定（FMLClientSetupEvent 仅客户端触发，早于世界加载）。
        // DH 是可选模组：未安装时不能触碰 DhSnowService（其类引用 DH API，
        // 缺失会 NoClassDefFoundError）。
        modEventBus.addListener((FMLClientSetupEvent e) -> {
            if (ModList.get().isLoaded("distanthorizons")) {
                DhSnowService.init();
            }
        });
        // 专用服务器没有 Screen 类；无条件注册会让服务端在构造期加载客户端类而崩溃
        if (FMLEnvironment.dist.isClient()) {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, EstfcConfigScreen::new);
        }
    }
}
