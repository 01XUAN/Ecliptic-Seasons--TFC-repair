package me.xuan.estfc;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import org.slf4j.Logger;

/**
 * Ecliptic Seasons ↔ TerraFirmaCraft 桥接模组。
 * <p>
 * 功能：
 * <ul>
 *   <li>将 TFC 日历月份映射为节气模组的 24 节气</li>
 *   <li>使用 TFC 气候模型判断雨/雪，替代节气模组自身的判断</li>
 *   <li>禁用节气模组的独立天气循环，完全由 TFC 接管</li>
 * </ul>
 */
@Mod(EclipticSeasonsTFCrepair.MODID)
public class EclipticSeasonsTFCrepair {
    public static final String MODID = "estfc";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EclipticSeasonsTFCrepair(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        LOGGER.info("节气模组 ↔ 群峦模组 兼容层已加载 (Ecliptic Seasons TFC Compat loaded)");
    }
}
