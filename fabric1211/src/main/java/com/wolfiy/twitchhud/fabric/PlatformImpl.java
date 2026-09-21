package com.wolfiy.twitchhud.fabric;

import com.wolfiy.twitchhud.core.Platform;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.net.URI;

public final class PlatformImpl implements Platform {
    public static final PlatformImpl INSTANCE = new PlatformImpl();

    private PlatformImpl() {
    }

    public static Style styleFor(String fontId) {
        return Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of("twitchhud", fontId)));
    }

    @Override
    public void copyToClipboard(String text) {
        MinecraftClient.getInstance().keyboard.setClipboard(text);
    }

    @Override
    public void openUrl(String url) {
        Util.getOperatingSystem().open(URI.create(url));
    }

    @Override
    public int measureTextWidth(String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.textRenderer.getWidth(text);
    }

    @Override
    public int measureFontTextWidth(String text, String fontId) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.textRenderer.getWidth(Text.literal(text).setStyle(styleFor(fontId)));
    }

    @Override
    public int screenWidth() {
        return MinecraftClient.getInstance().getWindow().getScaledWidth();
    }

    @Override
    public int screenHeight() {
        return MinecraftClient.getInstance().getWindow().getScaledHeight();
    }
}
