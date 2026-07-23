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
 * <p>使用 HeaderAndFooterLayout + GridLayout 2 列布局替代 NeoForge 默认的
 * 纯列表界面。5 个配置分组带有醒目标题，底部 [返回]/[完成] 按钮可直接返回
 * 上一级界面。</p>
 */
public final class EstfcConfigScreen extends Screen {

    private final Screen parent;

    private static final int INPUT_WIDTH = 200;
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
        grid.defaultCellSetting().paddingHorizontal(4).paddingBottom(4).alignHorizontallyCenter();
        GridLayout.RowHelper rows = grid.createRowHelper(2);

        addSectionHeader(rows, "estfc.config.section.general");
        addBoolRow(rows, Config.ENABLE_TFC_COMPAT, "estfc.config.enableTFCCompat");

        addSectionHeader(rows, "estfc.config.section.snowTemperature");
        addDoubleRow(rows, Config.SNOW_APPEARANCE_TEMP, "estfc.config.snowAppearanceTemp");
        addDoubleRow(rows, Config.SNOW_FULL_COVERAGE_TEMP, "estfc.config.snowFullCoverageTemp");
        addDoubleRow(rows, Config.SNOW_SNOWFALL_FULL_TEMP, "estfc.config.snowSnowfallFullTemp");
        addDoubleRow(rows, Config.SNOW_MELT_TEMP, "estfc.config.snowMeltTemp");
        addDoubleRow(rows, Config.DH_UNLOADED_TEMP_OFFSET, "estfc.config.dhUnloadedTempOffset");
        addIntRow(rows, Config.UNLOADED_TEMP_SAMPLE_RANGE, "estfc.config.unloadedTempSampleRange");

        addSectionHeader(rows, "estfc.config.section.climateCalculation");
        addDoubleRow(rows, Config.ELEVATION_LAPSE_RATE, "estfc.config.elevationLapseRate");
        addDoubleRow(rows, Config.SNOW_ACCUMULATION_SPEED, "estfc.config.snowAccumulationSpeedMultiplier");
        addDoubleRow(rows, Config.SNOW_MELT_SPEED, "estfc.config.snowMeltSpeedMultiplier");

        addSectionHeader(rows, "estfc.config.section.snowTexture");
        addBoolRow(rows, Config.SNOW_TEXTURE_SIMPLE_MODE, "estfc.config.snowTextureSimpleMode");
        addBoolRow(rows, Config.SNOW_TEXTURE_REPLACE_MODE, "estfc.config.snowTextureReplaceMode");
        addStringRow(rows, Config.SNOW_TEXTURE_DEFAULT_PATH, "estfc.config.snowTextureDefaultPath");

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

    private void addSectionHeader(GridLayout.RowHelper rows, String key) {
        StringWidget title = new StringWidget(
                Component.translatable(key).withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD),
                this.font);
        title.setHeight(ROW_HEIGHT + 4);
        rows.addChild(title, 2);
    }

    private void addBoolRow(GridLayout.RowHelper rows,
                            ModConfigSpec.BooleanValue spec, String key) {
        Component label = Component.translatable(key);
        rows.addChild(makeLabel(label));
        rows.addChild(makeBoolButton(spec, key));
    }

    private void addDoubleRow(GridLayout.RowHelper rows,
                              ModConfigSpec.DoubleValue spec, String key) {
        Component label = Component.translatable(key).append(
                Component.literal("  ").append(rangeHint(spec))
                        .withStyle(ChatFormatting.GRAY));
        rows.addChild(makeLabel(label));
        rows.addChild(makeDoubleBox(spec, key));
    }

    private void addIntRow(GridLayout.RowHelper rows,
                           ModConfigSpec.IntValue spec, String key) {
        Component label = Component.translatable(key).append(
                Component.literal("  ").append(rangeHint(spec))
                        .withStyle(ChatFormatting.GRAY));
        rows.addChild(makeLabel(label));
        rows.addChild(makeIntBox(spec, key));
    }

    @SuppressWarnings("unchecked")
    private void addStringRow(GridLayout.RowHelper rows,
                              ModConfigSpec.ConfigValue<?> spec, String key) {
        Component label = Component.translatable(key);
        rows.addChild(makeLabel(label));
        rows.addChild(makeStringBox((ModConfigSpec.ConfigValue<String>) spec, key));
    }

    @SuppressWarnings("unchecked")
    private void addListRow(GridLayout.RowHelper rows,
                            ModConfigSpec.ConfigValue<?> spec, String key) {
        Component label = Component.translatable(key);
        rows.addChild(makeLabel(label));
        rows.addChild(makeListBox(
                (ModConfigSpec.ConfigValue<List<String>>) spec, key));
    }

    private StringWidget makeLabel(Component text) {
        StringWidget label = new StringWidget(text, this.font);
        label.setHeight(ROW_HEIGHT);
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
        attachTooltip(btn, key);
        return btn;
    }

    private EditBox makeDoubleBox(ModConfigSpec.DoubleValue spec, String key) {
        EditBox box = new EditBox(this.font, INPUT_WIDTH, ROW_HEIGHT,
                Component.translatable(key));
        box.setValue(formatDouble(spec.get()));
        box.setResponder(makeDoubleResponder(spec, box));
        attachTooltip(box, key);
        return box;
    }

    private EditBox makeIntBox(ModConfigSpec.IntValue spec, String key) {
        EditBox box = new EditBox(this.font, INPUT_WIDTH, ROW_HEIGHT,
                Component.translatable(key));
        box.setValue(String.valueOf(spec.get()));
        box.setResponder(makeIntResponder(spec, box));
        attachTooltip(box, key);
        return box;
    }

    private EditBox makeStringBox(ModConfigSpec.ConfigValue<String> spec, String key) {
        EditBox box = new EditBox(this.font, INPUT_WIDTH, ROW_HEIGHT,
                Component.translatable(key));
        box.setValue(spec.get());
        box.setResponder(spec::set);
        attachTooltip(box, key);
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
        attachTooltip(box, key);
        return box;
    }

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

    private String rangeHint(ModConfigSpec.DoubleValue spec) {
        var range = spec.getSpec().getRange();
        if (range == null) return "";
        return "[" + range.getMin() + ", " + range.getMax() + "]";
    }

    private String rangeHint(ModConfigSpec.IntValue spec) {
        var range = spec.getSpec().getRange();
        if (range == null) return "";
        return "[" + range.getMin() + ", " + range.getMax() + "]";
    }

    private static String formatDouble(double value) {
        if (value == (long) value) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private void attachTooltip(AbstractWidget widget, String key) {
        String tooltipKey = key + ".tooltip";
        MutableComponent tip;

        Component translated = Component.translatable(tooltipKey);
        if (!tooltipKey.equals(translated.getString())) {
            tip = Component.translatable(key)
                    .withStyle(ChatFormatting.BOLD)
                    .append(Component.literal("\n\n").withStyle(ChatFormatting.RESET))
                    .append(translated);
        } else {
            tip = Component.translatable(key).withStyle(ChatFormatting.BOLD);
        }

        widget.setTooltip(Tooltip.create(tip));
    }

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
