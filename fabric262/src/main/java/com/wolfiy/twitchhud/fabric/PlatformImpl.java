package com.wolfiy.twitchhud.fabric;

import com.wolfiy.twitchhud.core.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.net.URI;

public final class PlatformImpl implements Platform {
    public static final PlatformImpl INSTANCE = new PlatformImpl();

    private PlatformImpl() {
    }

    public static Style styleFor(String fontId) {
        return Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("twitchhud", fontId)));
    }

    @Override
    public void copyToClipboard(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    @Override
    public void openUrl(String url) {
        Util.getPlatform().openUri(URI.create(url));
    }

    @Override
    public int measureTextWidth(String text) {
        Minecraft client = Minecraft.getInstance();
        return client.font.width(text);
    }

    @Override
    public int measureFontTextWidth(String text, String fontId) {
        Minecraft client = Minecraft.getInstance();
        return client.font.width(Component.literal(text).setStyle(styleFor(fontId)));
    }

    @Override
    public int screenWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    @Override
    public int screenHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }
}
