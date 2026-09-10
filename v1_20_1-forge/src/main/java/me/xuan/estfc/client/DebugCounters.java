package me.xuan.estfc.client;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 临时诊断计数器。放在普通类里 —— mixin 类不允许有非 private 的静态字段
 * （`InvalidMixinException: ... contains non-private static field`）。
 * 定位完连同相关 mixin 一起删除。
 */
public final class DebugCounters {

    /** ES findModel 判定「有雪」的次数 */
    public static final AtomicInteger FIND_MODEL_HITS = new AtomicInteger();
    /** 客户端应用增量覆雪包的次数 / 位置数 */
    public static final AtomicInteger DELTA_PACKETS = new AtomicInteger();
    public static final AtomicInteger DELTA_POSITIONS = new AtomicInteger();
    /** 客户端应用登录全量包的次数 */
    public static final AtomicInteger INITIAL_PACKETS = new AtomicInteger();
    /** 贴图替换：找到 estfc 贴图 / 没找到（保留 TFC 原贴图 = 看不出覆雪） */
    public static final AtomicInteger TEX_SWAPPED = new AtomicInteger();
    public static final AtomicInteger TEX_MISSING = new AtomicInteger();

    private DebugCounters() {}
}
