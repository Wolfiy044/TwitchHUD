package com.wolfiy.twitchhud.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.Consumer;
import java.util.function.Function;

public final class RoundedSlider {
    private static final int TRACK_COLOR = 0xFF3A3A42;
    private static final int FILL_COLOR = 0xFF9147FF;
    private static final int HANDLE_COLOR = 0xFFFFFFFF;

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final double min;
    private final double max;
    private final Function<Double, String> labelFormatter;
    private final Consumer<Double> onChange;
    private double value;
    private boolean dragging;

    public RoundedSlider(int x, int y, int width, int height, double min, double max, double initialValue,
                          Function<Double, String> labelFormatter, Consumer<Double> onChange) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.min = min;
        this.max = max;
        this.value = initialValue;
        this.labelFormatter = labelFormatter;
        this.onChange = onChange;
    }

    private double progress() {
        return Math.max(0.0, Math.min(1.0, (value - min) / (max - min)));
    }

    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        context.fill(x, y, x + width, y + height, TRACK_COLOR);
        int fillWidth = (int) (width * progress());
        context.fill(x, y, x + fillWidth, y + height, FILL_COLOR);
        int handleX = x + fillWidth;
        context.fill(Math.max(x, handleX - 1), y - 1, Math.min(x + width, handleX + 2), y + height + 1, HANDLE_COLOR);

        String label = labelFormatter.apply(value);
        var font = Minecraft.getInstance().font;
        int textWidth = font.width(label);
        context.text(font, label, x + width / 2 - textWidth / 2, y + (height - 8) / 2, 0xFFFFFFFF, true);
    }

    public boolean isHovered(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isHovered(mouseX, mouseY)) {
            dragging = true;
            updateFromMouse(mouseX);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX) {
        if (dragging) {
            updateFromMouse(mouseX);
            return true;
        }
        return false;
    }

    public void mouseReleased() {
        dragging = false;
    }

    private void updateFromMouse(double mouseX) {
        double ratio = Math.max(0.0, Math.min(1.0, (mouseX - x) / width));
        value = min + ratio * (max - min);
        onChange.accept(value);
    }
}
