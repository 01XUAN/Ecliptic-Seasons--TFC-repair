package me.xuan.estfc.client.gui;

import me.xuan.estfc.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.layouts.*;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.*;

/**
 * estfc 配置界面。
 *
 * <p>布局：顶部标签栏 + 中间可滚动条目 + 底部动态按钮。</p>
 * <p>按钮行为：无修改→Cancel/Reset/Done；有修改→放弃修改/Reset/保存修改。</p>
 * <p>ESC/放弃修改 在有修改时弹出确认。</p>
 */
public final class EstfcConfigScreen extends Screen {

    private final Screen parent;

    // ── 布局常量 ──
    private static final int LABEL_W = 150;
    private static final int INPUT_W = 160;
    private static final int ROW_H = 20;
    private static final int TAB_W = 100;

    // ── 所有配置值（用于快照对比和重置） ──
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final ModConfigSpec.ConfigValue[] ALL_VALUES = {
        Config.SNOW_APPEARANCE_TEMP,
        Config.SNOW_FULL_COVERAGE_TEMP, Config.SNOW_MELT_TEMP,
        Config.SNOW_RAINFALL_NO_SNOW, Config.SNOW_RAINFALL_FULL_SNOW,
        Config.DH_TEMP_PREFETCH_RADIUS, Config.DH_TEMP_REQUESTS_PER_TICK,
        Config.DH_TEMP_SERVER_THREADS, Config.DH_TEMP_SERVER_SUBMIT_PER_TICK,
        Config.TICK_CHUNK_INTERVAL_TICKS, Config.BIOME_SYNC_INTERVAL_TICKS,
        Config.SNOW_TEXTURE_SIMPLE_MODE, Config.SNOW_TEXTURE_REPLACE_MODE,
        Config.TFC_CONNECTED_TEXTURE,
        Config.SNOW_TFC_BLOCK_PREFIXES, Config.SNOW_TFC_SIMPLE_SNOW_BLOCKS,
        Config.SNOW_BLOCK_NAMESPACES,
        Config.SNOW_TFC_LEAVES_BLOCKS
    };

    // ── 标签定义 ──
    private static final String[] TAB_KEYS = {
            "estfc.config.section.general",
            "estfc.config.section.snowTemperature",
            "estfc.config.section.climateCalculation",
            "estfc.config.section.snowTexture",
            "estfc.config.section.tfcBlockSnowPrefixes"
    };

    private int activeTab = 0;
    private HeaderAndFooterLayout layout;

    // 列表编辑器展开状态（同时只允许一个展开）
    private String expandedListKey = null;

    // 打开界面时的值快照 — 用于检测修改
    private final Object[] initialValues = new Object[ALL_VALUES.length];

    // ── 构造 ──

    public EstfcConfigScreen(ModContainer modContainer, Screen parent) {
        super(Component.translatable("estfc.config.title"));
        this.parent = parent;
        captureInitialValues();
    }

    private void captureInitialValues() {
        for (int i = 0; i < ALL_VALUES.length; i++) {
            initialValues[i] = ALL_VALUES[i].get();
        }
    }

    private boolean hasModifications() {
        for (int i = 0; i < ALL_VALUES.length; i++) {
            if (!Objects.equals(ALL_VALUES[i].get(), initialValues[i])) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════
    //  init
    // ══════════════════════════════════════════════════

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this, 61, 33);

        // ── 头部：标题 + 标签栏 ──
        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(8));
        header.addChild(
                new StringWidget(Component.translatable("estfc.config.title"), this.font),
                LayoutSettings::alignHorizontallyCenter);

        LinearLayout tabRow = header.addChild(LinearLayout.horizontal()).spacing(2);
        for (int i = 0; i < TAB_KEYS.length; i++) {
            final int idx = i;
            Component label = (i == activeTab)
                    ? Component.translatable(TAB_KEYS[i]).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                    : Component.translatable(TAB_KEYS[i]);
            tabRow.addChild(Button.builder(label, b -> switchTab(idx))
                    .width(TAB_W).build());
        }

        // ── 中部：可滚动内容 ──
        GridLayout grid = new GridLayout();
        grid.defaultCellSetting().paddingHorizontal(5).paddingBottom(3).alignHorizontallyCenter();
        GridLayout.RowHelper rows = grid.createRowHelper(2);
        buildTabContent(rows, activeTab);

        ScrollableLayout scroll = new ScrollableLayout(this.minecraft, grid, layout.getContentHeight());
        layout.addToContents(scroll);

        // ── 底部：动态按钮 ──
        buildFooter();

        layout.visitWidgets(this::addRenderableWidget);
        layout.arrangeElements();
    }

    private void buildFooter() {
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal()).spacing(8);
        int btnW = (this.width - 40) / 3;
        boolean modified = hasModifications();

        // 左：取消 / 放弃修改
        var leftText = modified
                ? Component.translatable("estfc.config.discard")
                : Component.translatable("estfc.config.back");
        footer.addChild(Button.builder(leftText, b -> onClose(false)).width(btnW).build());

        // 中：重置（始终显示）
        footer.addChild(Button.builder(
                Component.translatable("estfc.config.reset"),
                b -> confirmReset()).width(btnW).build());

        // 右：完成 / 保存修改
        var rightText = modified
                ? Component.translatable("estfc.config.save")
                : Component.translatable("estfc.config.done");
        footer.addChild(Button.builder(rightText, b -> onClose(true)).width(btnW).build());
    }

    private void switchTab(int idx) {
        if (idx == activeTab) return;
        activeTab = idx;
        clearWidgets();
        init();
    }

    // ══════════════════════════════════════════════════
    //  Close / ESC — 有修改时确认，无修改直接关闭
    // ══════════════════════════════════════════════════

    @Override
    public void onClose() {
        onClose(false);
    }

    private void onClose(boolean save) {
        if (save) {
            Config.SPEC.save();
            Objects.requireNonNull(this.minecraft).setScreen(this.parent);
            return;
        }
        // 放弃修改 — 有修改时确认
        if (hasModifications()) {
            minecraft.setScreen(new ConfirmScreen(
                    confirmed -> {
                        if (confirmed) {
                            Objects.requireNonNull(minecraft).setScreen(parent);
                        } else {
                            minecraft.setScreen(EstfcConfigScreen.this);
                        }
                    },
                    Component.translatable("estfc.config.discardConfirm"),
                    Component.translatable("estfc.config.discardConfirm.message")
            ));
        } else {
            Objects.requireNonNull(this.minecraft).setScreen(this.parent);
        }
    }

    // ══════════════════════════════════════════════════
    //  Reset — 确认后重置
    // ══════════════════════════════════════════════════

    private void confirmReset() {
        minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        resetToDefaults();
                    }
                    minecraft.setScreen(EstfcConfigScreen.this);
                },
                Component.translatable("estfc.config.resetConfirm"),
                Component.translatable("estfc.config.resetConfirm.message")
        ));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void resetToDefaults() {
        for (var v : ALL_VALUES) v.set(v.getDefault());
        clearWidgets();
        init();
    }

    // ══════════════════════════════════════════════════
    //  各标签页内容
    // ══════════════════════════════════════════════════

    private void buildTabContent(GridLayout.RowHelper rows, int tab) {
        switch (tab) {
            case 0 -> {
                addBoolRow(rows, Config.TFC_CONNECTED_TEXTURE, "estfc.config.tfcConnectedTexture");
            }
            case 1 -> {
                addDoubleRow(rows, Config.SNOW_APPEARANCE_TEMP,
                        "estfc.config.snowAppearanceTemp", "°C");
                addDoubleRow(rows, Config.SNOW_FULL_COVERAGE_TEMP,
                        "estfc.config.snowFullCoverageTemp", "°C");
                addDoubleRow(rows, Config.SNOW_MELT_TEMP,
                        "estfc.config.snowMeltTemp", "°C");
                addIntRow(rows, Config.SNOW_RAINFALL_NO_SNOW,
                        "estfc.config.snowRainfallNoSnow", " mm");
                addIntRow(rows, Config.SNOW_RAINFALL_FULL_SNOW,
                        "estfc.config.snowRainfallFullSnow", " mm");
                addIntRow(rows, Config.DH_TEMP_PREFETCH_RADIUS,
                        "estfc.config.dhTempPrefetchRadius", " blocks");
                addIntRow(rows, Config.DH_TEMP_REQUESTS_PER_TICK,
                        "estfc.config.dhTempRequestsPerTick", " requests");
                addIntRow(rows, Config.DH_TEMP_SERVER_THREADS,
                        "estfc.config.dhTempServerThreads", " threads");
                addIntRow(rows, Config.DH_TEMP_SERVER_SUBMIT_PER_TICK,
                        "estfc.config.dhTempServerSubmitPerTick", " points");
            }
            case 2 -> {
                addIntRow(rows, Config.TICK_CHUNK_INTERVAL_TICKS,
                        "estfc.config.tickChunkIntervalTicks", " ticks");
                addIntRow(rows, Config.BIOME_SYNC_INTERVAL_TICKS,
                        "estfc.config.biomeSyncIntervalTicks", " ticks");
            }
            case 3 -> {
                addBoolRow(rows, Config.SNOW_TEXTURE_SIMPLE_MODE,
                        "estfc.config.snowTextureSimpleMode");
                addBoolRow(rows, Config.SNOW_TEXTURE_REPLACE_MODE,
                        "estfc.config.snowTextureReplaceMode");
            }
            case 4 -> {
                buildListEditor(rows,
                        (ModConfigSpec.ConfigValue<List<String>>) (Object) Config.SNOW_TFC_BLOCK_PREFIXES,
                        "estfc.config.tfcSnowBlockPrefixes");
                buildListEditor(rows,
                        (ModConfigSpec.ConfigValue<List<String>>) (Object) Config.SNOW_TFC_SIMPLE_SNOW_BLOCKS,
                        "estfc.config.tfcSimpleSnowBlocks");
                buildListEditor(rows,
                        (ModConfigSpec.ConfigValue<List<String>>) (Object) Config.SNOW_TFC_LEAVES_BLOCKS,
                        "estfc.config.tfcLeavesSnowBlocks");
                buildListEditor(rows,
                        (ModConfigSpec.ConfigValue<List<String>>) (Object) Config.SNOW_BLOCK_NAMESPACES,
                        "estfc.config.blockNamespaces");
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  条目工厂
    // ══════════════════════════════════════════════════

    private StringWidget makeLabel(String key) {
        StringWidget label = new StringWidget(
                Component.translatable(key).withStyle(ChatFormatting.GRAY), this.font);
        label.setHeight(ROW_H);
        label.setWidth(LABEL_W);
        label.alignLeft();
        return label;
    }

    private void addBoolRow(GridLayout.RowHelper rows,
                            ModConfigSpec.BooleanValue spec, String key) {
        rows.addChild(makeLabel(key));
        var btn = CycleButton.onOffBuilder(spec.get()).displayOnlyValue()
                .create(0, 0, INPUT_W, ROW_H, CommonComponents.EMPTY, (w, v) -> spec.set(v));
        attachTooltip(btn, key);
        rows.addChild(btn);
    }

    private void addDoubleRow(GridLayout.RowHelper rows,
                              ModConfigSpec.DoubleValue spec, String key, String unit) {
        rows.addChild(makeLabel(key));
        rows.addChild(makeNumberBox(spec, key, unit,
                Double::parseDouble, EstfcConfigScreen::formatDouble));
    }

    private void addIntRow(GridLayout.RowHelper rows,
                           ModConfigSpec.IntValue spec, String key, String unit) {
        rows.addChild(makeLabel(key));
        rows.addChild(makeNumberBox(spec, key, unit,
                Integer::parseInt, String::valueOf));
    }

    @SuppressWarnings("unchecked")
    private <V extends Number> EditBox makeNumberBox(
            ModConfigSpec.ConfigValue<V> spec, String key, String unit,
            java.util.function.Function<String, V> parser,
            java.util.function.Function<V, String> formatter) {
        EditBox box = new EditBox(this.font, INPUT_W, ROW_H, Component.translatable(key));
        box.setValue(formatter.apply(spec.get()));
        box.setResponder(text -> {
            try {
                V v = parser.apply(text);
                var r = spec.getSpec().getRange();
                if (r != null && !r.test(v)) { box.setTextColor(0xFFFF0000); return; }
                spec.set(v);
                box.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
            } catch (Exception e) { box.setTextColor(0xFFFF0000); }
        });
        attachTooltip(box, key, unit);
        return box;
    }


    // ── list editor (expandable, per-item rows) ──

    private void buildListEditor(GridLayout.RowHelper rows,
                                 ModConfigSpec.ConfigValue<List<String>> spec, String key) {
        List<String> items = new ArrayList<>(spec.get());
        boolean expanded = key.equals(expandedListKey);

        MutableComponent headerText = expanded
                ? Component.literal("▼ ").append(Component.translatable(key))
                : Component.literal("▶ ").append(Component.translatable(key))
                    .append(" (" + items.size() + ")");
        var headerBtn = Button.builder(headerText.withStyle(ChatFormatting.GOLD), b -> {
            expandedListKey = expanded ? null : key;
            clearWidgets();
            init();
        }).build();
        attachTooltip(headerBtn, key);
        rows.addChild(headerBtn, 2);

        if (!expanded) return;

        int listBoxW = LABEL_W + INPUT_W + 5 - 28;

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            EditBox box = new EditBox(this.font, listBoxW, ROW_H, Component.empty());
            box.setValue(items.get(i));
            box.setResponder(text -> {
                List<String> cur = new ArrayList<>(spec.get());
                if (idx < cur.size()) { cur.set(idx, text); spec.set(cur); }
            });

            Button del = Button.builder(Component.literal("✕").withStyle(ChatFormatting.RED),
                            b -> deleteListItem(spec, idx))
                    .width(20).build();

            LinearLayout row = LinearLayout.horizontal().spacing(4);
            row.addChild(box);
            row.addChild(del);
            rows.addChild(row, 2);
        }

        rows.addChild(Button.builder(
                        Component.translatable("estfc.config.listAdd"),
                        b -> addListItem(spec))
                .build(), 2);
    }

    private void addListItem(ModConfigSpec.ConfigValue<List<String>> spec) {
        List<String> cur = new ArrayList<>(spec.get());
        cur.add("");
        spec.set(cur);
        clearWidgets();
        init();
    }

    private void deleteListItem(ModConfigSpec.ConfigValue<List<String>> spec, int idx) {
        List<String> cur = new ArrayList<>(spec.get());
        if (idx < cur.size()) cur.remove(idx);
        spec.set(cur);
        clearWidgets();
        init();
    }

    // ══════════════════════════════════════════════════
    //  Tooltip
    // ══════════════════════════════════════════════════

    private void attachTooltip(AbstractWidget widget, String key) {
        attachTooltip(widget, key, null);
    }

    private void attachTooltip(AbstractWidget widget, String key, String unit) {
        String tk = key + ".tooltip";
        MutableComponent tip = Component.translatable(key).withStyle(ChatFormatting.BOLD);
        MutableComponent detail = Component.translatable(tk);
        boolean has = !tk.equals(detail.getString());
        if (unit != null || has) tip.append(Component.literal("\n\n").withStyle(ChatFormatting.RESET));
        if (unit != null) tip.append(Component.literal(unit).withStyle(ChatFormatting.GRAY));
        if (has) {
            if (unit != null) tip.append(Component.literal("\n"));
            tip.append(detail.withStyle(ChatFormatting.GRAY));
        }
        widget.setTooltip(Tooltip.create(tip));
    }

    // ══════════════════════════════════════════════════
    //  Util
    // ══════════════════════════════════════════════════

    private static String formatDouble(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
