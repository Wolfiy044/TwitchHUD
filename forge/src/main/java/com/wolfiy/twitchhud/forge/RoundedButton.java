package com.wolfiy.twitchhud.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class RoundedButton {
    private static final int COLOR_NORMAL = 0xFF3A3A42;
    private static final int COLOR_HOVER = 0xFF4E4E58;
    private static final int COLOR_TEXT = 0xFFF0F0F0;

    private int x;
    private int y;
    private int width;
    private int height;
    private String label;
    private final Runnable onClick;

    public RoundedButton(int x, int y, int width, int height, String label, Runnable onClick) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.label = label;
        this.onClick = onClick;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public boolean isHovered(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean hovered = isHovered(mouseX, mouseY);
        int color = 0xFF000000 | (hovered ? COLOR_HOVER : COLOR_NORMAL);
        graphics.fill(x, y, x + width, y + height, color);
        int textWidth = Minecraft.getInstance().font.width(label);
        int textX = x + (width - textWidth) / 2;
        int textY = y + (height - 8) / 2;
        graphics.drawString(Minecraft.getInstance().font, label, textX, textY, COLOR_TEXT, false);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isHovered(mouseX, mouseY)) {
            onClick.run();
            return true;
        }
        return false;
    }
}
