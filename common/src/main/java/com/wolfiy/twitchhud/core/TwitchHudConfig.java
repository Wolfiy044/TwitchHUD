package com.wolfiy.twitchhud.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TwitchHudConfig {
    public static final class WidgetPosition {
        private double x;
        private double y;

        public WidgetPosition() {
        }

        public WidgetPosition(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public void set(double x, double y) {
            this.x = clamp01(x);
            this.y = clamp01(y);
        }

        public WidgetPosition copy() {
            return new WidgetPosition(x, y);
        }
    }

    public static final class WidgetSize {
        private double width;
        private double height;

        public WidgetSize() {
        }

        public WidgetSize(double width, double height) {
            set(width, height);
        }

        public double width() {
            return width;
        }

        public double height() {
            return height;
        }

        public void set(double width, double height) {
            this.width = Math.max(72.0, Math.min(600.0, width));
            this.height = Math.max(22.0, Math.min(300.0, height));
        }

        public WidgetSize copy() {
            return new WidgetSize(width, height);
        }
    }

    public static final class WidgetStyle {
        private double backgroundOpacity = 0.85;
        private double textScale = 1.0;
        private int textColor = 0xFFFFFF;
        private double textOpacity = 1.0;
        private String fontId = "nunito";

        public WidgetStyle() {
        }

        public WidgetStyle(
                double backgroundOpacity,
                double textScale,
                int textColor,
                double textOpacity,
                String fontId
        ) {
            this.backgroundOpacity = backgroundOpacity;
            this.textScale = textScale;
            this.textColor = textColor;
            this.textOpacity = textOpacity;
            this.fontId = fontId;
            normalize();
        }

        public double backgroundOpacity() {
            return backgroundOpacity;
        }

        public void backgroundOpacity(double value) {
            backgroundOpacity = clamp01(value);
        }

        public double textScale() {
            return textScale;
        }

        public void textScale(double value) {
            textScale = Math.max(0.5, Math.min(2.0, value));
        }

        public int textColor() {
            return textColor;
        }

        public void textColor(int value) {
            textColor = value & 0xFFFFFF;
        }

        public double textOpacity() {
            return textOpacity;
        }

        public void textOpacity(double value) {
            textOpacity = clamp01(value);
        }

        public String fontId() {
            return fontId;
        }

        public void fontId(String value) {
            fontId = normalizeFontId(value);
        }

        public WidgetStyle copy() {
            return new WidgetStyle(
                    backgroundOpacity,
                    textScale,
                    textColor,
                    textOpacity,
                    fontId
            );
        }

        private void normalize() {
            backgroundOpacity = clamp01(backgroundOpacity);
            textScale = Math.max(
                    0.5,
                    Math.min(2.0, textScale)
            );
            textColor &= 0xFFFFFF;
            textOpacity = clamp01(textOpacity);
            fontId = normalizeFontId(fontId);
        }
    }

    private static final Logger LOGGER =
            Logger.getLogger("TwitchHUD");
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    private volatile String channel = "";
    private double x = 0.02;
    private double y = 0.35;
    private double width = 340;
    private double height = 260;
    private double backgroundOpacity = 0.35;
    private double textScale = 1.0;
    private int messageColor = 0xFFFFFF;
    private double messageOpacity = 1.0;
    private String fontId = "nunito";
    private boolean showEmotes = true;
    private boolean showTimestamps = false;
    private boolean showBadges = true;
    private boolean compactMode = false;
    private volatile int maxMessages = 100;
    private int fadeAfterSeconds = 0;
    private volatile boolean filterBots = true;
    private volatile boolean filterSpam = true;
    private volatile boolean filterBadWords = false;
    private boolean locked = true;
    private boolean showOutsideGame = false;
    private boolean showViewerWidget = false;
    private boolean showTitleWidget = false;
    private boolean showHypeTrainWidget = false;
    private boolean showFollowerWidget = false;
    private boolean showCreatorGoals = false;
    private WidgetPosition viewerPosition =
            new WidgetPosition(0.78, 0.04);
    private WidgetPosition titlePosition =
            new WidgetPosition(0.60, 0.09);
    private WidgetPosition hypeTrainPosition =
            new WidgetPosition(0.60, 0.15);
    private WidgetPosition followerPosition =
            new WidgetPosition(0.76, 0.30);
    private Map<String, WidgetPosition> goalPositions =
            new LinkedHashMap<>();
    private Map<String, WidgetSize> widgetSizes =
            new LinkedHashMap<>();
    private Map<String, WidgetStyle> widgetStyles =
            new LinkedHashMap<>();
    private List<String> blockedWords = new ArrayList<>();
    private Set<String> hiddenUsers = new LinkedHashSet<>();

    public static TwitchHudConfig load(Path path) {
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(
                    path,
                    StandardCharsets.UTF_8
            )) {
                TwitchHudConfig loaded =
                        GSON.fromJson(reader, TwitchHudConfig.class);
                if (loaded != null) {
                    loaded.normalize();
                    return loaded;
                }
            } catch (IOException | RuntimeException error) {
                LOGGER.log(
                        Level.WARNING,
                        "TwitchHUD: config load failed",
                        error
                );
            }
        }
        return new TwitchHudConfig();
    }

    public static TwitchHudConfig fromJson(String json) {
        TwitchHudConfig loaded =
                GSON.fromJson(json, TwitchHudConfig.class);
        if (loaded == null) {
            return null;
        }
        loaded.normalize();
        return loaded;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public void save(Path path) {
        try {
            AtomicFiles.writeString(path, toJson());
        } catch (IOException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: config save failed",
                    error
            );
        }
    }

    public void copyFrom(TwitchHudConfig source) {
        source.normalize();
        channel = source.channel;
        x = source.x;
        y = source.y;
        width = source.width;
        height = source.height;
        backgroundOpacity = source.backgroundOpacity;
        textScale = source.textScale;
        messageColor = source.messageColor;
        messageOpacity = source.messageOpacity;
        fontId = source.fontId;
        showEmotes = source.showEmotes;
        showTimestamps = source.showTimestamps;
        showBadges = source.showBadges;
        compactMode = source.compactMode;
        maxMessages = source.maxMessages;
        fadeAfterSeconds = source.fadeAfterSeconds;
        filterBots = source.filterBots;
        filterSpam = source.filterSpam;
        filterBadWords = source.filterBadWords;
        locked = source.locked;
        showOutsideGame = source.showOutsideGame;
        showViewerWidget = source.showViewerWidget;
        showTitleWidget = source.showTitleWidget;
        showHypeTrainWidget = source.showHypeTrainWidget;
        showFollowerWidget = source.showFollowerWidget;
        showCreatorGoals = source.showCreatorGoals;
        viewerPosition = source.viewerPosition.copy();
        titlePosition = source.titlePosition.copy();
        hypeTrainPosition = source.hypeTrainPosition.copy();
        followerPosition = source.followerPosition.copy();
        goalPositions = new LinkedHashMap<>();
        for (Map.Entry<String, WidgetPosition> entry
                : source.goalPositions.entrySet()) {
            goalPositions.put(
                    entry.getKey(),
                    entry.getValue().copy()
            );
        }
        widgetSizes = new LinkedHashMap<>();
        for (Map.Entry<String, WidgetSize> entry
                : source.widgetSizes.entrySet()) {
            widgetSizes.put(
                    entry.getKey(),
                    entry.getValue().copy()
            );
        }
        widgetStyles = new LinkedHashMap<>();
        for (Map.Entry<String, WidgetStyle> entry
                : source.widgetStyles.entrySet()) {
            widgetStyles.put(
                    entry.getKey(),
                    entry.getValue().copy()
            );
        }
        blockedWords = new ArrayList<>(source.blockedWords);
        hiddenUsers = new LinkedHashSet<>(source.hiddenUsers);
        normalize();
    }

    private void normalize() {
        channel = channel == null ? "" : channel;
        fontId = normalizeFontId(fontId);

        List<String> normalizedBlockedWords =
                new ArrayList<>();
        if (blockedWords != null) {
            for (String word : blockedWords) {
                if (word != null && !word.isBlank()) {
                    normalizedBlockedWords.add(
                            word.trim().toLowerCase(
                                    Locale.ROOT
                            )
                    );
                }
            }
        }
        blockedWords = normalizedBlockedWords;

        Set<String> normalizedHiddenUsers =
                new LinkedHashSet<>();
        if (hiddenUsers != null) {
            for (String user : hiddenUsers) {
                if (user != null && !user.isBlank()) {
                    normalizedHiddenUsers.add(
                            user.trim().toLowerCase(
                                    Locale.ROOT
                            )
                    );
                }
            }
        }
        hiddenUsers = normalizedHiddenUsers;
        viewerPosition = ensure(
                viewerPosition,
                0.78,
                0.04
        );
        titlePosition = ensure(
                titlePosition,
                0.60,
                0.09
        );
        hypeTrainPosition = ensure(
                hypeTrainPosition,
                0.60,
                0.15
        );
        followerPosition = ensure(
                followerPosition,
                0.76,
                0.30
        );
        goalPositions = goalPositions == null
                ? new LinkedHashMap<>()
                : goalPositions;
        widgetSizes = widgetSizes == null
                ? new LinkedHashMap<>()
                : widgetSizes;
        widgetSizes.remove("pinned");
        for (WidgetSize size : widgetSizes.values()) {
            if (size != null) {
                size.set(size.width(), size.height());
            }
        }
        widgetStyles = widgetStyles == null
                ? new LinkedHashMap<>()
                : widgetStyles;
        widgetStyles.remove("pinned");
        for (WidgetStyle style : widgetStyles.values()) {
            if (style != null) {
                style.normalize();
            }
        }
        width = Math.max(120, width);
        height = Math.max(60, height);
        backgroundOpacity = clamp01(backgroundOpacity);
        messageColor &= 0xFFFFFF;
        messageOpacity = clamp01(messageOpacity);
        textScale = Math.max(0.5, Math.min(2.0, textScale));
        maxMessages = Math.max(20, Math.min(500, maxMessages));
        fadeAfterSeconds = Math.max(
                0,
                Math.min(300, fadeAfterSeconds)
        );
        x = clamp01(x);
        y = clamp01(y);
    }

    private static WidgetPosition ensure(
            WidgetPosition position,
            double x,
            double y
    ) {
        if (position == null) {
            return new WidgetPosition(x, y);
        }
        position.set(position.x(), position.y());
        return position;
    }

    public WidgetPosition widgetPosition(String key) {
        return switch (key) {
            case "viewer" -> viewerPosition;
            case "title" -> titlePosition;
            case "hype" -> hypeTrainPosition;
            case "follower" -> followerPosition;
            default -> {
                if (key.startsWith("goal:")) {
                    String goalKey = key.substring(5);
                    yield goalPositions.computeIfAbsent(
                            goalKey,
                            this::defaultGoalPosition
                    );
                }
                yield new WidgetPosition(0.5, 0.5);
            }
        };
    }

    public void moveWidget(
            String key,
            double x,
            double y
    ) {
        widgetPosition(key).set(x, y);
    }

    public WidgetSize widgetSize(
            String key,
            double defaultWidth,
            double defaultHeight
    ) {
        WidgetSize stored = widgetSizes.get(key);
        if (stored == null) {
            return new WidgetSize(
                    defaultWidth,
                    defaultHeight
            );
        }
        return stored;
    }

    public void resizeWidget(
            String key,
            double width,
            double height,
            double defaultWidth,
            double defaultHeight
    ) {
        widgetSizes.computeIfAbsent(
                key,
                ignored -> new WidgetSize(
                        defaultWidth,
                        defaultHeight
                )
        ).set(width, height);
    }

    public void resetWidgetSize(String key) {
        widgetSizes.remove(key);
    }

    public WidgetStyle widgetStyle(String key) {
        WidgetStyle existing = widgetStyles.get(key);
        if (existing != null) {
            return existing;
        }

        if (key != null
                && key.startsWith("goal:")
                && !"goal:*".equals(key)) {
            WidgetStyle goalDefault =
                    widgetStyles.get("goal:*");
            if (goalDefault != null) {
                WidgetStyle copy = goalDefault.copy();
                widgetStyles.put(key, copy);
                return copy;
            }
        }

        WidgetStyle created = new WidgetStyle(
                backgroundOpacity,
                textScale,
                messageColor,
                messageOpacity,
                fontId
        );
        widgetStyles.put(key, created);
        return created;
    }

    public void resetWidgetStyle(String key) {
        widgetStyles.remove(key);
    }

    public void copyChatStyleToWidget(String key) {
        widgetStyles.put(
                key,
                new WidgetStyle(
                        backgroundOpacity,
                        textScale,
                        messageColor,
                        messageOpacity,
                        fontId
                )
        );
    }

    private WidgetPosition defaultGoalPosition(String key) {
        int slot = Math.floorMod(key.hashCode(), 4);
        return new WidgetPosition(
                0.58,
                0.38 + slot * 0.08
        );
    }

    private static String normalizeFontId(
            String value
    ) {
        if (value == null) {
            return "nunito";
        }
        String normalized = value.trim()
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "off", "nunito", "inter", "poppins" ->
                    normalized;
            default -> "nunito";
        };
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public String channel() {
        return channel;
    }

    public void channel(String channel) {
        this.channel = channel == null ? "" : channel;
    }

    public double x() {
        return x;
    }

    public void x(double x) {
        this.x = clamp01(x);
    }

    public double y() {
        return y;
    }

    public void y(double y) {
        this.y = clamp01(y);
    }

    public double width() {
        return width;
    }

    public void width(double width) {
        this.width = Math.max(120, width);
    }

    public double height() {
        return height;
    }

    public void height(double height) {
        this.height = Math.max(60, height);
    }

    public double backgroundOpacity() {
        return backgroundOpacity;
    }

    public void backgroundOpacity(double backgroundOpacity) {
        this.backgroundOpacity = clamp01(backgroundOpacity);
    }

    public double textScale() {
        return textScale;
    }

    public void textScale(double textScale) {
        this.textScale = Math.max(
                0.5,
                Math.min(2.0, textScale)
        );
    }

    public int messageColor() {
        return messageColor;
    }

    public void messageColor(int messageColor) {
        this.messageColor = messageColor & 0xFFFFFF;
    }

    public double messageOpacity() {
        return messageOpacity;
    }

    public void messageOpacity(double messageOpacity) {
        this.messageOpacity = clamp01(messageOpacity);
    }

    public String fontId() {
        return fontId;
    }

    public void fontId(String fontId) {
        this.fontId = normalizeFontId(fontId);
    }

    public boolean showEmotes() {
        return showEmotes;
    }

    public void showEmotes(boolean showEmotes) {
        this.showEmotes = showEmotes;
    }

    public boolean showTimestamps() {
        return showTimestamps;
    }

    public void showTimestamps(boolean showTimestamps) {
        this.showTimestamps = showTimestamps;
    }

    public boolean showBadges() {
        return showBadges;
    }

    public void showBadges(boolean showBadges) {
        this.showBadges = showBadges;
    }

    public boolean compactMode() {
        return compactMode;
    }

    public void compactMode(boolean compactMode) {
        this.compactMode = compactMode;
    }

    public int maxMessages() {
        return maxMessages;
    }

    public void maxMessages(int maxMessages) {
        this.maxMessages = Math.max(
                20,
                Math.min(500, maxMessages)
        );
    }

    public int fadeAfterSeconds() {
        return fadeAfterSeconds;
    }

    public void fadeAfterSeconds(int fadeAfterSeconds) {
        this.fadeAfterSeconds = Math.max(
                0,
                Math.min(300, fadeAfterSeconds)
        );
    }

    public boolean filterBots() {
        return filterBots;
    }

    public void filterBots(boolean filterBots) {
        this.filterBots = filterBots;
    }

    public boolean filterSpam() {
        return filterSpam;
    }

    public void filterSpam(boolean filterSpam) {
        this.filterSpam = filterSpam;
    }

    public boolean filterBadWords() {
        return filterBadWords;
    }

    public void filterBadWords(boolean filterBadWords) {
        this.filterBadWords = filterBadWords;
    }

    public boolean locked() {
        return locked;
    }

    public void locked(boolean locked) {
        this.locked = locked;
    }

    public boolean showOutsideGame() {
        return showOutsideGame;
    }

    public void showOutsideGame(boolean showOutsideGame) {
        this.showOutsideGame = showOutsideGame;
    }

    public boolean showViewerWidget() {
        return showViewerWidget;
    }

    public void showViewerWidget(boolean value) {
        showViewerWidget = value;
    }

    public boolean showTitleWidget() {
        return showTitleWidget;
    }

    public void showTitleWidget(boolean value) {
        showTitleWidget = value;
    }

    public boolean showHypeTrainWidget() {
        return showHypeTrainWidget;
    }

    public void showHypeTrainWidget(boolean value) {
        showHypeTrainWidget = value;
    }

    public boolean showFollowerWidget() {
        return showFollowerWidget;
    }

    public void showFollowerWidget(boolean value) {
        showFollowerWidget = value;
    }

    public boolean showCreatorGoals() {
        return showCreatorGoals;
    }

    public void showCreatorGoals(boolean value) {
        showCreatorGoals = value;
    }

    public synchronized List<String> blockedWords() {
        return List.copyOf(blockedWords);
    }

    public synchronized void addBlockedWord(String word) {
        String trimmed = word == null
                ? ""
                : word.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.isEmpty()
                && !blockedWords.contains(trimmed)) {
            blockedWords.add(trimmed);
        }
    }

    public synchronized void removeBlockedWord(String word) {
        if (word != null) {
            blockedWords.remove(
                    word.trim().toLowerCase(Locale.ROOT)
            );
        }
    }

    public synchronized Set<String> hiddenUsers() {
        return Set.copyOf(hiddenUsers);
    }

    public synchronized boolean isHiddenUser(
            String username
    ) {
        return username != null
                && hiddenUsers.contains(
                        username.trim().toLowerCase(Locale.ROOT)
                );
    }

    public synchronized void hideUser(String username) {
        if (username != null && !username.isBlank()) {
            hiddenUsers.add(
                    username.trim().toLowerCase(Locale.ROOT)
            );
        }
    }

    public synchronized void unhideUser(String username) {
        if (username != null && !username.isBlank()) {
            hiddenUsers.remove(
                    username.trim().toLowerCase(Locale.ROOT)
            );
        }
    }
}
