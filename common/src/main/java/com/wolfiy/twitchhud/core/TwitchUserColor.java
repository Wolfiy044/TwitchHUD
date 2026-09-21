package com.wolfiy.twitchhud.core;

import java.util.Locale;

public final class TwitchUserColor {
    private static final int[] DEFAULT_COLORS = {
            0xFF0000, 0x0000FF, 0x008000, 0xB22222, 0xFF7F50,
            0x9ACD32, 0xFF4500, 0x2E8B57, 0xDAA520, 0xD2691E,
            0x5F9EA0, 0x1E90FF, 0xFF69B4, 0x8A2BE2, 0x00FF7F
    };

    private TwitchUserColor() {
    }

    public static int resolve(String colorTag, String username) {
        if (colorTag != null
                && colorTag.matches("#[0-9A-Fa-f]{6}")) {
            return Integer.parseInt(
                    colorTag.substring(1),
                    16
            );
        }

        String key = username == null ? "" : username.toLowerCase(Locale.ROOT);
        return DEFAULT_COLORS[Math.floorMod(key.hashCode(), DEFAULT_COLORS.length)];
    }
}
