package com.wolfiy.twitchhud.core;

public interface Platform {
    void copyToClipboard(String text);

    void openUrl(String url);

    int measureTextWidth(String text);

    int measureFontTextWidth(String text, String fontId);

    int screenWidth();

    int screenHeight();
}
