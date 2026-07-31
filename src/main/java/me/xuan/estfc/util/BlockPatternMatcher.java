package me.xuan.estfc.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
    private static final Map<String, Pattern> PATTERN_CACHE = new HashMap<>();

    /**
     * 提取 block ID 并用 {@link #matchesEntry} 逐一匹配 entries。
     */
    public static boolean matches(BlockState state, Set<String> entries) {
        ResourceLocation id = state.getBlockHolder().getKey().location();
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
            try {
                Pattern p = PATTERN_CACHE.computeIfAbsent(entry,
                        k -> Pattern.compile(k.substring(REGEX_PREFIX.length())));
                return p.matcher(path).matches();
            } catch (Exception ignored) {
                return false;
            }
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
