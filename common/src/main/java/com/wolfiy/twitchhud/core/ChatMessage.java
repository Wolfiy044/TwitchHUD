package com.wolfiy.twitchhud.core;

import java.util.List;

public final class ChatMessage {
    public enum Kind {
        CHAT,
        SUB,
        RESUB,
        SUBGIFT,
        RAID,
        ANNOUNCEMENT,
        FOLLOW,
        CHEER,
        REDEMPTION,
        SYSTEM
    }

    private final String id;
    private final String username;
    private final String displayName;
    private final String text;
    private final int color;
    private final boolean broadcaster;
    private final boolean moderator;
    private final boolean subscriber;
    private final List<EmoteRef> emotes;
    private final List<Badge> badges;
    private final long timestamp;
    private final Kind kind;
    private final String systemText;
    private boolean hidden;

    public ChatMessage(String id, String username, String displayName, String text, int color,
                        boolean broadcaster, boolean moderator, boolean subscriber,
                        List<EmoteRef> emotes, List<Badge> badges, long timestamp,
                        Kind kind, String systemText) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.text = text;
        this.color = color;
        this.broadcaster = broadcaster;
        this.moderator = moderator;
        this.subscriber = subscriber;
        this.emotes = emotes;
        this.badges = badges;
        this.timestamp = timestamp;
        this.kind = kind;
        this.systemText = systemText;
    }

    public String id() {
        return id;
    }

    public String username() {
        return username;
    }

    public String displayName() {
        return displayName;
    }

    public String text() {
        return text;
    }

    public int color() {
        return color;
    }

    public boolean broadcaster() {
        return broadcaster;
    }

    public boolean moderator() {
        return moderator;
    }

    public boolean subscriber() {
        return subscriber;
    }

    public List<EmoteRef> emotes() {
        return emotes;
    }

    public List<Badge> badges() {
        return badges;
    }

    public long timestamp() {
        return timestamp;
    }

    public Kind kind() {
        return kind;
    }

    public String systemText() {
        return systemText;
    }

    public String copyText() {
        if (text != null && !text.isBlank()) {
            return text;
        }
        return systemText == null ? "" : systemText;
    }

    public boolean hidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public record EmoteRef(String emoteId, int start, int end) {
    }

    public record Badge(String setId, String version) {
    }
}
