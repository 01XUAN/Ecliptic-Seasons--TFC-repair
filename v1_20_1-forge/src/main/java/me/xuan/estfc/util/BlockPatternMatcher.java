package me.xuan.estfc.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 配置条目 → 方块 ID 的统一匹配工具。
 * <ul>
 *   <li>{@code regex:...} → 正则匹配路径</li>
 *   <li>以 / 结尾 → 路径前缀匹配</li>
 *   <li>含 : → 完整 ID 精确匹配</li>
 *   <li>其他 → 路径前缀匹配</li>
 * </ul>
 */
public final class BlockPatternMatcher {

    private BlockPatternMatcher() {}

    public static final String REGEX_PREFIX = "regex:";

    /** 编译为永不匹配的模式 —— 用于缓存非法正则 */
    private static final Pattern NEVER_MATCHES = Pattern.compile("(?!)");

    /** 被渲染/区块编译线程并发 computeIfAbsent，必须是并发结构 */
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private static Pattern patternFor(String entry) {
        return PATTERN_CACHE.computeIfAbsent(entry, k -> {
            try {
                return Pattern.compile(k.substring(REGEX_PREFIX.length()));
            } catch (PatternSyntaxException e) {
                return NEVER_MATCHES;
            }
        });
    }

    /**
     * 提取 block ID 并用 {@link #matchesEntry} 逐一匹配 entries。
     */
    public static boolean matches(BlockState state, Set<String> entries) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) return false;
        return matches(id.getPath(), id.toString(), entries);
    }

    public static boolean matches(String path, String fullId, Set<String> entries) {
        for (String entry : entries) {
            if (matchesEntry(path, fullId, entry)) return true;
        }
        return false;
    }

    public static boolean matchesEntry(String path, String fullId, String entry) {
        if (entry.startsWith(REGEX_PREFIX)) {
            return patternFor(entry).matcher(path).matches();
        }
        if (entry.endsWith("/")) {
            return path.startsWith(entry);
        }
        if (entry.contains(":")) {
            return fullId.equals(entry);
        }
        return path.startsWith(entry);
    }
}
