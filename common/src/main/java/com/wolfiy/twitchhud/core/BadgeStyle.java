package com.wolfiy.twitchhud.core;

public final class BadgeStyle {
    private BadgeStyle() {
    }

    public record Style(String abbreviation, int color) {
    }

    public static Style forSetId(String setId) {
        return switch (setId) {
            case "broadcaster" -> new Style("B", 0xE91916);
            case "moderator" -> new Style("MOD", 0x2ECC71);
            case "vip" -> new Style("VIP", 0xE685ED);
            case "subscriber" -> new Style("SUB", 0x9147FF);
            case "staff" -> new Style("STAFF", 0x7A2B8A);
            case "admin" -> new Style("ADMIN", 0xFA4B4B);
            case "global_mod" -> new Style("GMOD", 0x2ECC71);
            case "partner" -> new Style("PTNR", 0x9147FF);
            case "premium" -> new Style("PRIME", 0x1E9DFF);
            case "founder" -> new Style("FNDR", 0xFF6B35);
            default -> new Style(setId.length() >= 3 ? setId.substring(0, 3).toUpperCase() : setId.toUpperCase(), 0x777777);
        };
    }
}
