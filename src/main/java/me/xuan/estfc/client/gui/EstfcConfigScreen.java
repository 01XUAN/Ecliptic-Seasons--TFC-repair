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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * estfc 自定义配置界面。
 *
 * <p>2 列布局：左侧标签（灰色），右侧输入控件。
 * 范围提示合并到 tooltip 中，保持界面简洁。</p>
 */
public final class EstfcConfigScreen extends Screen {

    private final Screen parent;

    private static final int LABEL_WIDTH = 140;
    private static final int INPUT_WIDTH = 120;
    private static final int ROW_HEIGHT = 20;

    public EstfcConfigScreen(ModContainer modContainer, Screen parent) {
        super(Component.translatable("estfc.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 61, 33);

        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(8));
        header.addChild(
                new StringWidget(Component.translatable("estfc.config.title"), this.font),
                LayoutSettings::alignHorizontallyCenter);

        GridLayout grid = new GridLayout();
        grid.defaultCellSetting().paddingHorizontal(5).paddingBottom(3).alignHorizontallyCenter();
        GridLayout.RowHelper rows = grid.createRowHelper(2);

        // ---- General ----
        addSectionHeader(rows, "estfc.config.section.general");
        addBoolRow(rows, Config.ENABLE_TFC_COMPAT, "estfc.config.enableTFCCompat");

        // ---- Snow Temperature ----
        addSectionHeader(rows, "estfc.config.section.snowTemperature");
        addDoubleRow(rows, Config.SNOW_APPEARANCE_TEMP, "estfc.config.snowAppearanceTemp", "°C");
        addDoubleRow(rows, Config.SNOW_FULL_COVERAGE_TEMP, "estfc.config.snowFullCoverageTemp", "°C");
        addDoubleRow(rows, Config.SNOW_MELT_TEMP, "estfc.config.snowMeltTemp", "°C");
        addDoubleRow(rows, Config.DH_UNLOADED_TEMP_OFFSET, "estfc.config.dhUnloadedTempOffset", "°C");
        addIntRow(rows, Config.UNLOADED_TEMP_SAMPLE_RANGE, "estfc.config.unloadedTempSampleRange", " blocks");

        // ---- Climate Calculation ----
        addSectionHeader(rows, "estfc.config.section.climateCalculation");
        addIntRow(rows, Config.TICK_CHUNK_INTERVAL_TICKS, "estfc.config.tickChunkIntervalTicks", " ticks");
        addIntRow(rows, Config.BIOME_SYNC_INTERVAL_TICKS, "estfc.config.biomeSyncIntervalTicks", " ticks");

        // ---- Snow Texture ----
        addSectionHeader(rows, "estfc.config.section.snowTexture");
        addBoolRow(rows, Config.SNOW_TEXTURE_SIMPLE_MODE, "estfc.config.snowTextureSimpleMode");
        addBoolRow(rows, Config.SNOW_TEXTURE_REPLACE_MODE, "estfc.config.snowTextureReplaceMode");
        addStringRow(rows, Config.SNOW_TEXTURE_DEFAULT_PATH, "estfc.config.snowTextureDefaultPath");

        // ---- TFC Block Snow Prefixes ----
        addSectionHeader(rows, "estfc.config.section.tfcBlockSnowPrefixes");
        addListRow(rows, Config.SNOW_TFC_BLOCK_PREFIXES, "estfc.config.tfcSnowBlockPrefixes");
        addListRow(rows, Config.SNOW_TFC_SIMPLE_SNOW_BLOCKS, "estfc.config.tfcSimpleSnowBlocks");

        ScrollableLayout scrollable = new ScrollableLayout(
                this.minecraft, grid, layout.getContentHeight());
        layout.addToContents(scrollable);

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal()).spacing(8);
        int buttonWidth = this.width / 2 - 40;
        footer.addChild(Button.builder(
                Component.translatable("estfc.config.back"),
                btn -> onClose(false)).width(buttonWidth).build());
        footer.addChild(Button.builder(
                Component.translatable("estfc.config.done"),
                btn -> onClose(true)).width(buttonWidth).build());

        layout.visitWidgets(this::addRenderableWidget);
        layout.arrangeElements();
    }

    // ==================== row helpers ====================

    private void addSectionHeader(GridLayout.RowHelper rows, String key) {
        StringWidget title = new StringWidget(
                Component.translatable(key).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                this.font);
        title.setHeight(ROW_HEIGHT + 6);
        rows.addChild(title, 2);
    }

    private void addBoolRow(GridLayout.RowHelper rows,
                            ModConfigSpec.BooleanValue spec, String key) {
        rows.addChild(makeLabel(Component.translatable(key)));
        rows.addChild(makeBoolButton(spec, key));
    }

    private void addDoubleRow(GridLayout.RowHelper rows,
                              ModConfigSpec.DoubleValue spec, String key, String unit) {
        rows.addChild(makeLabel(Component.translatable(key)));
        rows.addChild(makeDoubleBox(spec, key, unit));
    }

    private void addIntRow(GridLayout.RowHelper rows,
                           ModConfigSpec.IntValue spec, String key, String unit) {
        rows.addChild(makeLabel(Component.translatable(key)));
        rows.addChild(makeIntBox(spec, key, unit));
    }

    private void addStringRow(GridLayout.RowHelper rows,
                              ModConfigSpec.ConfigValue<?> spec, String key) {
        rows.addChild(makeLabel(Component.translatable(key)));
        rows.addChild(makeStringBox((ModConfigSpec.ConfigValue<String>) spec, key));
    }

    @SuppressWarnings("unchecked")
    private void addListRow(GridLayout.RowHelper rows,
                            ModConfigSpec.ConfigValue<?> spec, String key) {
        rows.addChild(makeLabel(Component.translatable(key)));
        rows.addChild(makeListBox(
                (ModConfigSpec.ConfigValue<List<String>>) spec, key));
    }

    // ==================== widget factories ====================

    private StringWidget makeLabel(Component text) {
        StringWidget label = new StringWidget(
                text.copy().withStyle(ChatFormatting.GRAY), this.font);
        label.setHeight(ROW_HEIGHT);
        label.setWidth(LABEL_WIDTH);
        label.alignLeft();
        return label;
    }

    private CycleButton<Boolean> makeBoolButton(
            ModConfigSpec.BooleanValue spec, String key) {
        CycleButton<Boolean> btn = CycleButton.onOffBuilder(spec.get())
                .displayOnlyValue()
                .create(0, 0, INPUT_WIDTH, ROW_HEIGHT,
                        CommonComponents.EMPTY,
                        (widget, value) -> spec.set(value));
        attachTooltip(btn, key, null);
        return btn;
    }

    private EditBox makeDoubleBox(ModConfigSpec.DoubleValue spec, String key, String unit) {
        EditBox box = new EditBox(this.font, INPUT_WIDTH, ROW_HEIGHT,
                Component.translatable(key));
        box.setValue(formatDouble(spec.get()));
        box.setResponder(makeDoubleResponder(spec, box));
        attachTooltip(box, key, unit);
        return box;
    }

    private EditBox makeIntBox(ModConfigSpec.IntValue spec, String key, String unit) {
        EditBox box = new EditBox(this.font, INPUT_WIDTH, ROW_HEIGHT,
                Component.translatable(key));
        box.setValue(String.valueOf(spec.get()));
        box.setResponder(makeIntResponder(spec, box));
        attachTooltip(box, key, unit);
        return box;
    }

    private EditBox makeStringBox(ModConfigSpec.ConfigValue<String> spec, String key) {
        EditBox box = new EditBox(this.font, INPUT_WIDTH, ROW_HEIGHT,
                Component.translatable(key));
        box.setValue(spec.get());
        box.setResponder(spec::set);
        attachTooltip(box, key, null);
        return box;
    }

    private EditBox makeListBox(ModConfigSpec.ConfigValue<List<String>> spec, String key) {
        EditBox box = new EditBox(this.font, INPUT_WIDTH, ROW_HEIGHT,
                Component.translatable(key));
        box.setValue(String.join(", ", spec.get()));
        box.setResponder(text -> {
            List<String> items = Arrays.stream(text.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            spec.set(items);
        });
        attachTooltip(box, key, null);
        return box;
    }

    // ==================== responders ====================

    private Consumer<String> makeDoubleResponder(
            ModConfigSpec.DoubleValue spec, EditBox box) {
        return text -> {
            try {
                double value = Double.parseDouble(text);
                var range = spec.getSpec().getRange();
                if (range != null && !range.test(value)) {
                    box.setTextColor(0xFFFF0000);
                    return;
                }
                spec.set(value);
                box.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
            } catch (NumberFormatException e) {
                box.setTextColor(0xFFFF0000);
            }
        };
    }

    private Consumer<String> makeIntResponder(
            ModConfigSpec.IntValue spec, EditBox box) {
        return text -> {
            try {
                int value = Integer.parseInt(text);
                var range = spec.getSpec().getRange();
                if (range != null && !range.test(value)) {
                    box.setTextColor(0xFFFF0000);
                    return;
                }
                spec.set(value);
                box.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
            } catch (NumberFormatException e) {
                box.setTextColor(0xFFFF0000);
            }
        };
    }

    // ==================== tooltip ====================

    /**
     * 构建 tooltip：标题（粗体）+ 范围（若有）+ 详细说明（若有）。
     */
    private void attachTooltip(AbstractWidget widget, String key, String unit) {
        String tooltipKey = key + ".tooltip";
        MutableComponent tip = Component.translatable(key).withStyle(ChatFormatting.BOLD);

        MutableComponent detail = Component.translatable(tooltipKey);
        boolean hasDetail = !tooltipKey.equals(detail.getString());

        if (unit != null || hasDetail) {
            tip.append(Component.literal("\n\n").withStyle(ChatFormatting.RESET));
        }
        if (unit != null) {
            tip.append(Component.literal("Unit: " + unit).withStyle(ChatFormatting.GRAY));
        }
        if (hasDetail) {
            if (unit != null) tip.append(Component.literal("\n"));
            tip.append(detail.withStyle(ChatFormatting.GRAY));
        }

        widget.setTooltip(Tooltip.create(tip));
    }

    private static String formatDouble(double value) {
        if (value == (long) value) return String.valueOf((long) value);
        return String.valueOf(value);
    }

    // ==================== close ====================

    @Override
    public void onClose() {
        onClose(true);
    }

    private void onClose(boolean save) {
        if (save) {
            Config.SPEC.save();
        }
        Objects.requireNonNull(this.minecraft).setScreen(this.parent);
    }
}
