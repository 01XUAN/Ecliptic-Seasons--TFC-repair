package me.xuan.estfc.client.gui;

import me.xuan.estfc.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.layouts.*;
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
 * <p>布局：顶部标签栏 + 中间可滚动条目 + 底部 Cancel / Reset / Done。</p>
 */
public final class EstfcConfigScreen extends Screen {

    private final Screen parent;

    // ── 布局常量 ──
    private static final int LABEL_W = 150;
    private static final int INPUT_W = 160;
    private static final int ROW_H = 20;
    private static final int TAB_W = 100;

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

    // ── 构造 ──

    public EstfcConfigScreen(ModContainer modContainer, Screen parent) {
        super(Component.translatable("estfc.config.title"));
        this.parent = parent;
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

        // ── 底部：三按钮 ──
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal()).spacing(8);
        int btnW = (this.width - 40) / 3;
        footer.addChild(Button.builder(
                Component.translatable("estfc.config.back"),
                b -> onClose(false)).width(btnW).build());
        footer.addChild(Button.builder(
                Component.translatable("estfc.config.reset"),
                b -> resetToDefaults()).width(btnW).build());
        footer.addChild(Button.builder(
                Component.translatable("estfc.config.done"),
                b -> onClose(true)).width(btnW).build());

        layout.visitWidgets(this::addRenderableWidget);
        layout.arrangeElements();
    }

    private void switchTab(int idx) {
        if (idx == activeTab) return;
        activeTab = idx;
        clearWidgets();
        init();
    }

    // ══════════════════════════════════════════════════
    //  各标签页内容
    // ══════════════════════════════════════════════════

    private void buildTabContent(GridLayout.RowHelper rows, int tab) {
        switch (tab) {
            case 0 -> {
                addBoolRow(rows, Config.ENABLE_TFC_COMPAT, "estfc.config.enableTFCCompat");
                addBoolRow(rows, Config.TFC_CONNECTED_TEXTURE, "estfc.config.tfcConnectedTexture");
            }
            case 1 -> {
                addDoubleRow(rows, Config.SNOW_APPEARANCE_TEMP,
                        "estfc.config.snowAppearanceTemp", "°C");
                addDoubleRow(rows, Config.SNOW_FULL_COVERAGE_TEMP,
                        "estfc.config.snowFullCoverageTemp", "°C");
                addDoubleRow(rows, Config.SNOW_MELT_TEMP,
                        "estfc.config.snowMeltTemp", "°C");
                addDoubleRow(rows, Config.DH_UNLOADED_TEMP_OFFSET,
                        "estfc.config.dhUnloadedTempOffset", "°C");
                addIntRow(rows, Config.UNLOADED_TEMP_SAMPLE_RANGE,
                        "estfc.config.unloadedTempSampleRange", " blocks");
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
                addStringRow(rows, Config.SNOW_TEXTURE_DEFAULT_PATH,
                        "estfc.config.snowTextureDefaultPath");
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
            }
        }
    }

    // ══════════════════════════════════════════════════
    //  条目工厂
    // ══════════════════════════════════════════════════

    // ── label ──
    private StringWidget makeLabel(String key) {
        StringWidget label = new StringWidget(
                Component.translatable(key).withStyle(ChatFormatting.GRAY), this.font);
        label.setHeight(ROW_H);
        label.setWidth(LABEL_W);
        label.alignLeft();
        return label;
    }

    // ── bool ──
    private void addBoolRow(GridLayout.RowHelper rows,
                            ModConfigSpec.BooleanValue spec, String key) {
        rows.addChild(makeLabel(key));
        var btn = CycleButton.onOffBuilder(spec.get()).displayOnlyValue()
                .create(0, 0, INPUT_W, ROW_H, CommonComponents.EMPTY, (w, v) -> spec.set(v));
        attachTooltip(btn, key);
        rows.addChild(btn);
    }

    // ── number (double / int, unified) ──

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

    // ── string ──
    private void addStringRow(GridLayout.RowHelper rows,
                              ModConfigSpec.ConfigValue<String> spec, String key) {
        rows.addChild(makeLabel(key));
        EditBox box = new EditBox(this.font, INPUT_W, ROW_H, Component.translatable(key));
        box.setValue(spec.get());
        box.setResponder(spec::set);
        rows.addChild(box);
        attachTooltip(box, key);
    }

    // ── list editor (expandable, per-item rows) ──

    private void buildListEditor(GridLayout.RowHelper rows,
                                 ModConfigSpec.ConfigValue<List<String>> spec, String key) {
        List<String> items = new ArrayList<>(spec.get());
        boolean expanded = key.equals(expandedListKey);

        // Header: ▼/▶ Name (N items)  — spans 2 columns
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

        int listBoxW = LABEL_W + INPUT_W + 5 - 28; // minus delete button + spacing

        // Item rows
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

        // Add button — spans 2 columns
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
    //  Save / Reset / Close
    // ══════════════════════════════════════════════════

    @Override
    public void onClose() {
        onClose(true);
    }

    private void onClose(boolean save) {
        if (save) Config.SPEC.save();
        Objects.requireNonNull(this.minecraft).setScreen(this.parent);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void resetToDefaults() {
        ModConfigSpec.ConfigValue[] values = {
            Config.ENABLE_TFC_COMPAT, Config.SNOW_APPEARANCE_TEMP,
            Config.SNOW_FULL_COVERAGE_TEMP, Config.SNOW_MELT_TEMP,
            Config.DH_UNLOADED_TEMP_OFFSET, Config.UNLOADED_TEMP_SAMPLE_RANGE,
            Config.TICK_CHUNK_INTERVAL_TICKS, Config.BIOME_SYNC_INTERVAL_TICKS,
            Config.SNOW_TEXTURE_SIMPLE_MODE, Config.SNOW_TEXTURE_REPLACE_MODE,
            Config.SNOW_TEXTURE_DEFAULT_PATH, Config.TFC_CONNECTED_TEXTURE,
            Config.SNOW_TFC_BLOCK_PREFIXES, Config.SNOW_TFC_SIMPLE_SNOW_BLOCKS,
            Config.SNOW_TFC_LEAVES_BLOCKS
        };
        for (var v : values) v.set(v.getDefault());
        clearWidgets();
        init();
    }

    // ══════════════════════════════════════════════════
    //  Util
    // ══════════════════════════════════════════════════

    private static String formatDouble(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
