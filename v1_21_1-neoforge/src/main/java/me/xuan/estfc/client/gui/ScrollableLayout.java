package me.xuan.estfc.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractContainerWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 可滚动布局 — 包裹一个 {@link Layout} 子内容。
 * <p>内容超出最大高度时自动显示滚动条，支持鼠标滚轮和拖拽滚动条。</p>
 * <p>实现 {@link Layout}，通过 {@link #visitChildren} 暴露内部容器。</p>
 */
public class ScrollableLayout implements Layout {
    private static final int SCROLL_RATE = 12;
    private static final int BAR_W = 6;
    private static final int BAR_GAP = 4;
    private static final int BAR_MIN_H = 16;

    private final Layout content;
    private final ScrollContainer container;
    private int maxHeight;

    public ScrollableLayout(Minecraft minecraft, Layout content, int maxHeight) {
        this.content = content;
        this.maxHeight = Math.max(1, maxHeight);
        this.container = new ScrollContainer(minecraft);
    }

    public void setMaxHeight(int h) {
        this.maxHeight = Math.max(1, h);
        arrangeElements();
    }

    // ── Layout ──

    @Override
    public void arrangeElements() {
        this.content.arrangeElements();
        int cw = Math.max(1, this.content.getWidth());
        int ch = Math.max(1, this.content.getHeight());

        boolean bar = ch > this.maxHeight;
        int barReserved = bar ? BAR_W + BAR_GAP : 0;
        int w = cw + barReserved;
        int h = Math.min(ch, this.maxHeight);

        this.container.resize(w, Math.max(1, h));
        this.container.syncChildren();
        this.container.clampScroll();

        this.content.setX(this.container.getX());
        this.content.setY(this.container.getY() - this.container.scrollAmount);
    }

    @Override
    public void visitChildren(Consumer<LayoutElement> consumer) {
        consumer.accept(this.container);
    }

    @Override
    public void setX(int x) {
        this.container.setX(x);
        this.content.setX(x);
    }

    @Override
    public void setY(int y) {
        this.container.setY(y);
        this.content.setY(y - this.container.scrollAmount);
    }

    @Override
    public int getX() { return this.container.getX(); }
    @Override
    public int getY() { return this.container.getY(); }
    @Override
    public int getWidth() { return this.container.getWidth(); }
    @Override
    public int getHeight() { return this.container.getHeight(); }

    // ── Inner ──

    private class ScrollContainer extends AbstractContainerWidget {
        private final Minecraft minecraft;
        private final List<AbstractWidget> widgets = new ArrayList<>();
        int scrollAmount;
        boolean dragging;
        int grabOffset;

        ScrollContainer(Minecraft mc) {
            super(0, 0, 0, 0, CommonComponents.EMPTY);
            this.minecraft = mc;
        }

        void resize(int w, int h) { this.width = w; this.height = h; }

        void syncChildren() {
            widgets.clear();
            ScrollableLayout.this.content.visitWidgets(widgets::add);
        }

        int contentH() { return Math.max(1, ScrollableLayout.this.content.getHeight()); }
        int maxScroll() { return Math.max(0, contentH() - this.height); }
        boolean scrollable() { return maxScroll() > 0; }

        void clampScroll() { setScroll(scrollAmount); }

        void setScroll(int v) {
            scrollAmount = Mth.clamp(v, 0, maxScroll());
            ScrollableLayout.this.content.setY(this.getY() - scrollAmount);
        }

        // scrollbar
        int barX() { return getX() + width - BAR_W; }
        int barH() {
            if (!scrollable()) return height;
            return Mth.clamp(height * height / contentH(), BAR_MIN_H, height);
        }
        int barY() {
            if (!scrollable()) return getY();
            int travel = Math.max(1, height - barH());
            return getY() + scrollAmount * travel / Math.max(1, maxScroll());
        }

        boolean overBar(double mx, double my) {
            return scrollable()
                    && mx >= barX() && mx < barX() + BAR_W
                    && my >= getY() && my < getY() + height;
        }

        boolean overThumb(double mx, double my) {
            int by = barY(), bh = barH();
            return overBar(mx, my) && my >= by && my < by + bh;
        }

        // ── render ──

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            g.enableScissor(getX(), getY(), getX() + width, getY() + height);
            for (AbstractWidget w : widgets) {
                if (w.getY() + w.getHeight() > getY() && w.getY() < getY() + height) {
                    w.render(g, mx, my, pt);
                }
            }
            g.disableScissor();

            if (scrollable()) {
                int x0 = barX(), x1 = x0 + BAR_W;
                int y0 = getY(), y1 = y0 + height;
                int by = barY(), bh = barH();
                g.fill(x0, y0, x1, y1, 0x44000000);
                g.fill(x0, by, x1, by + bh, 0xAAFFFFFF);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput o) {}

        // ── input ──

        @Override
        public boolean mouseScrolled(double mx, double my, double sx, double sy) {
            if (!visible || !isMouseOver(mx, my)) return false;
            setScroll(scrollAmount - (int)(sy * SCROLL_RATE));
            return true;
        }

        @Override
        public boolean mouseClicked(double mx, double my, int btn) {
            if (!isMouseOver(mx, my)) return false;
            if (btn == 0 && overBar(mx, my)) {
                if (overThumb(mx, my)) {
                    grabOffset = (int)my - barY();
                } else {
                    grabOffset = barH() / 2;
                    scrollToMouse(my);
                }
                dragging = true;
                return true;
            }
            return super.mouseClicked(mx, my, btn);
        }

        @Override
        public boolean mouseReleased(double mx, double my, int btn) {
            dragging = false;
            return super.mouseReleased(mx, my, btn);
        }

        @Override
        public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
            if (dragging && btn == 0 && scrollable()) {
                scrollToMouse(my);
                return true;
            }
            return super.mouseDragged(mx, my, btn, dx, dy);
        }

        private void scrollToMouse(double my) {
            int travel = Math.max(1, height - barH());
            int local = (int)my - getY() - grabOffset;
            setScroll(local * Math.max(1, maxScroll()) / travel);
        }

        @Override
        public void setFocused(@Nullable GuiEventListener focused) {
            super.setFocused(focused);
            if (focused != null && minecraft.getLastInputType().isKeyboard()) {
                var area = getRectangle();
                var rect = focused.getRectangle();
                int topD = rect.top() - area.top();
                int botD = rect.bottom() - area.bottom();
                if (topD < 0) setScroll(scrollAmount + topD - SCROLL_RATE);
                else if (botD > 0) setScroll(scrollAmount + botD + SCROLL_RATE);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() { return widgets; }
    }
}
