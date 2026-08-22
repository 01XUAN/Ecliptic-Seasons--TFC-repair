package me.xuan.estfc.client;

import me.xuan.estfc.Config;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * voxy 远景覆雪定时刷新（反射调用，不硬依赖 voxy 类）。
 * 内置兼容版走 SeasonalSnowRefresher（异步后台重扫 LOD 存储）；
 * 兼容模组版走 VoxyTool.tryUpdate（其自身只在 ES 雪变更事件刷新）。
 */
public final class VoxyRefreshService {

    private record Target(String className, String methodName) {}

    private static final Target BUILTIN =
        new Target("me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalSnowRefresher", "startManually");
    private static final Target COMPAT =
        new Target("com.teamtea.eclipticseasons_voxycompact.compat.voxy.VoxyTool", "tryUpdate");

    private static final ConcurrentHashMap<Target, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<Target> FAILED = ConcurrentHashMap.newKeySet();

    private VoxyRefreshService() {}

    /** 客户端 tick（由 ClientConMixin 调用）。voxy 未装时静默跳过。 */
    public static void tick(Level level) {
        if (!Config.voxyPeriodicRefresh() && !Config.voxyPeriodicRefreshCompatVoxy()) return;
        if (level.getGameTime() % (Config.voxyPeriodicRefreshSeconds() * 20L) != 0) return;

        if (Config.voxyPeriodicRefresh()) invoke(BUILTIN);
        if (Config.voxyPeriodicRefreshCompatVoxy()) invoke(COMPAT);
    }

    private static void invoke(Target target) {
        if (FAILED.contains(target)) return;
        Method method = METHOD_CACHE.computeIfAbsent(target, t -> {
            try {
                return Class.forName(t.className()).getMethod(t.methodName());
            } catch (Exception e) {
                FAILED.add(t); // voxy 未装 / 类结构变化：负缓存，不再重试
                return null;
            }
        });
        if (method == null) return;
        try {
            method.invoke(null);
        } catch (Exception | LinkageError ignored) {
        }
    }
}
