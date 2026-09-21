package com.wolfiy.twitchhud.core;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModerationFilter {
    private static final Set<String> KNOWN_BOTS = Set.of(
            "nightbot", "streamelements", "streamlabs", "moobot",
            "fossabot", "wizebot", "botisimo", "deepbot", "coebot",
            "phantombot", "soundalerts"
    );
    private static final int MAX_TRACKED_USERS = 2048;
    private static final int PRUNE_INTERVAL = 256;
    private static final long HISTORY_TTL_MS = 30 * 60 * 1000L;

    private final TwitchHudConfig config;
    private final Map<String, RecentHistory> historyByUser =
            new ConcurrentHashMap<>();
    private final AtomicInteger registrations =
            new AtomicInteger();

    public ModerationFilter(TwitchHudConfig config) {
        this.config = config;
    }

    public boolean shouldHide(ChatMessage message) {
        String user = message.username()
                .toLowerCase(Locale.ROOT);

        if (config.isHiddenUser(user)) {
            return true;
        }

        if (message.kind() != ChatMessage.Kind.CHAT
                && message.kind() != ChatMessage.Kind.CHEER) {
            return false;
        }

        if (config.filterBots() && KNOWN_BOTS.contains(user)) {
            return true;
        }

        if (config.filterSpam() && isSpam(message)) {
            return true;
        }

        return config.filterBadWords()
                && containsBadWord(message.text());
    }

    private boolean isSpam(ChatMessage message) {
        String user = message.username()
                .toLowerCase(Locale.ROOT);
        long now = message.timestamp();
        RecentHistory history = historyByUser.computeIfAbsent(
                user,
                ignored -> new RecentHistory()
        );
        boolean repeated = history.registerAndCheckRepeat(
                message.text(),
                now
        );

        if (registrations.incrementAndGet() % PRUNE_INTERVAL == 0) {
            pruneHistory(now);
        }

        int longestRun = 1;
        int runLength = 1;
        int lastCodePoint = -1;
        for (int offset = 0;
                offset < message.text().length();) {
            int codePoint = message.text().codePointAt(offset);
            if (codePoint == lastCodePoint) {
                runLength++;
                longestRun = Math.max(longestRun, runLength);
            } else {
                runLength = 1;
            }
            lastCodePoint = codePoint;
            offset += Character.charCount(codePoint);
        }

        return repeated || longestRun > 12;
    }

    private void pruneHistory(long now) {
        historyByUser.entrySet().removeIf(entry ->
                now - entry.getValue().lastSeen()
                        > HISTORY_TTL_MS
        );

        if (historyByUser.size() <= MAX_TRACKED_USERS) {
            return;
        }

        int removeCount =
                historyByUser.size() - MAX_TRACKED_USERS;
        for (String user : historyByUser.keySet()) {
            if (removeCount-- <= 0) {
                break;
            }
            historyByUser.remove(user);
        }
    }

    private boolean containsBadWord(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : config.blockedWords()) {
            if (!word.isBlank()
                    && lower.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static final class RecentHistory {
        private String lastMessage = "";
        private volatile long lastTimestamp;
        private int repeatCount;

        synchronized boolean registerAndCheckRepeat(
                String text,
                long timestamp
        ) {
            boolean withinWindow =
                    timestamp - lastTimestamp < 15_000;
            boolean sameText =
                    text.equalsIgnoreCase(lastMessage);
            if (withinWindow && sameText) {
                repeatCount++;
            } else {
                repeatCount = 0;
            }
            lastMessage = text;
            lastTimestamp = timestamp;
            return repeatCount >= 2;
        }

        long lastSeen() {
            return lastTimestamp;
        }
    }
}
