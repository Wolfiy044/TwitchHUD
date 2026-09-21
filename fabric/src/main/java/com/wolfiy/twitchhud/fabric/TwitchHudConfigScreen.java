package com.wolfiy.twitchhud.fabric;

import com.wolfiy.twitchhud.core.ConnectionStatus;
import com.wolfiy.twitchhud.core.TwitchHudConfig;
import com.wolfiy.twitchhud.core.TwitchHudProfileStore;
import com.wolfiy.twitchhud.core.TwitchHudSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TwitchHudConfigScreen extends Screen {
    private enum Tab {
        GENERAL,
        CHAT,
        WIDGETS,
        FILTERS,
        PROFILES
    }

    private static final String[] FONT_IDS = {
            "off", "nunito", "inter", "poppins"
    };
    private static final String[] FONT_LABELS = {
            "Default", "Nunito", "Inter", "Poppins"
    };
    private static final int[] PALETTE = {
            0xFFFFFF, 0x9147FF, 0xFFD166, 0x6DB4FF,
            0xFF6B6B, 0xB6FFCE, 0xFFA500, 0xFF69B4,
            0x2ECC71, 0xE685ED, 0x777777, 0xD2691E
    };
    private static final int HANDLE_SIZE = 8;

    private final Screen parent;
    private final TwitchHudSession session;
    private final ChatOverlayRenderer renderer;
    private final Path configPath;
    private final TwitchHudConfig config;
    private final TwitchHudProfileStore profileStore;
    private final List<RoundedButton> buttons =
            new ArrayList<>();
    private final List<RoundedSlider> sliders =
            new ArrayList<>();

    private Tab tab = Tab.GENERAL;
    private TextFieldWidget channelField;
    private TextFieldWidget colorField;
    private TextFieldWidget widgetColorField;
    private TextFieldWidget newWordField;
    private TextFieldWidget profileField;
    private boolean draggingOverlay;
    private boolean resizingOverlay;
    private String draggingWidget;
    private String resizingWidget;
    private int widgetResizeX;
    private int widgetResizeY;
    private int widgetDefaultWidth;
    private int widgetDefaultHeight;
    private double dragOffsetX;
    private double dragOffsetY;
    private String actionStatus = "";
    private boolean widgetStyleEditor;
    private boolean settingsDirty;
    private String selectedWidgetKey = "viewer";
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    protected TwitchHudConfigScreen(
            Screen parent,
            TwitchHudSession session,
            ChatOverlayRenderer renderer,
            Path configPath
    ) {
        super(Text.literal("TwitchHUD Settings"));
        this.parent = parent;
        this.session = session;
        this.renderer = renderer;
        this.configPath = configPath;
        config = session.config();
        profileStore = new TwitchHudProfileStore(
                configPath.getParent()
        );
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        buttons.clear();
        sliders.clear();

        panelW = Math.min(318, width - 16);
        panelH = Math.min(248, height - 16);
        panelX = 8;
        panelY = height - panelH - 8;

        addTabs();

        int left = panelX + 10;
        int contentW = panelW - 20;
        int y = panelY + 40;

        switch (tab) {
            case GENERAL -> initGeneral(left, y, contentW);
            case CHAT -> initChat(left, y, contentW);
            case WIDGETS -> initWidgets(left, y, contentW);
            case FILTERS -> initFilters(left, y, contentW);
            case PROFILES -> initProfiles(left, y, contentW);
        }

        buttons.add(new RoundedButton(
                panelX + panelW - 64,
                panelY + panelH - 24,
                54,
                18,
                "Done",
                this::close
        ));
    }

    private void addTabs() {
        String[] labels = {
                "Main", "Chat", "Widgets", "Filters", "Profiles"
        };
        Tab[] tabs = Tab.values();
        int gap = 3;
        int available = panelW - 20 - gap * 4;
        int tabW = available / 5;
        int x = panelX + 10;
        int y = panelY + 10;

        for (int i = 0; i < tabs.length; i++) {
            Tab target = tabs[i];
            String label = target == tab
                    ? "[" + labels[i] + "]"
                    : labels[i];
            buttons.add(new RoundedButton(
                    x,
                    y,
                    tabW,
                    18,
                    label,
                    () -> {
                        tab = target;
                        clearAndInit();
                    }
            ));
            x += tabW + gap;
        }
    }

    private void initGeneral(
            int left,
            int y,
            int contentW
    ) {
        int fieldW = contentW - 84;
        channelField = new TextFieldWidget(
                textRenderer,
                left + 4,
                y + 2,
                fieldW - 8,
                18,
                Text.literal("Channel")
        );
        channelField.setDrawsBackground(false);
        channelField.setText(config.channel());
        channelField.setMaxLength(32);
        addDrawableChild(channelField);

        buttons.add(new RoundedButton(
                left + fieldW + 4,
                y,
                80,
                20,
                "Connect",
                () -> connectChannel()
        ));
        y += 27;

        buttons.add(new RoundedButton(
                left,
                y,
                contentW,
                20,
                session.eventAlertsConnected()
                        ? "Twitch events: CONNECTED"
                        : "Authorize Twitch events",
                this::authorizeEvents
        ));
        y += 25;

        buttons.add(new RoundedButton(
                left,
                y,
                contentW,
                20,
                "Show outside game: "
                        + onOff(config.showOutsideGame()),
                () -> {
                    config.showOutsideGame(
                            !config.showOutsideGame()
                    );
                    persist();
                    clearAndInit();
                }
        ));
        y += 25;

        buttons.add(new RoundedButton(
                left,
                y,
                contentW,
                20,
                "Chat layout locked: "
                        + onOff(config.locked()),
                () -> {
                    config.locked(!config.locked());
                    persist();
                    clearAndInit();
                }
        ));
        y += 25;

        buttons.add(new RoundedButton(
                left,
                y,
                contentW,
                20,
                "Refresh Twitch data",
                session::refreshLiveData
        ));
    }

    private void initChat(
            int left,
            int y,
            int contentW
    ) {
        sliders.add(new RoundedSlider(
                left, y, contentW, 16,
                0.0, 1.0, config.backgroundOpacity(),
                value -> "Background: "
                        + Math.round(value * 100) + "%",
                value -> {
                    config.backgroundOpacity(value);
                    markDirty();
                }
        ));
        y += 22;

        sliders.add(new RoundedSlider(
                left, y, contentW, 16,
                0.5, 2.0, config.textScale(),
                value -> "Text size: "
                        + String.format("%.1fx", value),
                value -> {
                    config.textScale(value);
                    markDirty();
                }
        ));
        y += 22;

        sliders.add(new RoundedSlider(
                left, y, contentW, 16,
                0.2, 1.0, config.messageOpacity(),
                value -> "Text opacity: "
                        + Math.round(value * 100) + "%",
                value -> {
                    config.messageOpacity(value);
                    markDirty();
                }
        ));
        y += 22;

        colorField = new TextFieldWidget(
                textRenderer,
                left + 4,
                y + 2,
                64,
                18,
                Text.literal("Color")
        );
        colorField.setDrawsBackground(false);
        colorField.setMaxLength(6);
        colorField.setText(
                String.format("%06X", config.messageColor())
        );
        colorField.setChangedListener(
                this::onColorFieldChanged
        );
        addDrawableChild(colorField);

        buttons.add(new RoundedButton(
                left + 72,
                y,
                104,
                20,
                "Font: " + currentFontLabel(),
                this::cycleFont
        ));
        buttons.add(new RoundedButton(
                left + 180,
                y,
                contentW - 180,
                20,
                "Color",
                this::cycleColor
        ));
        y += 25;

        int third = third(contentW);
        buttons.add(new RoundedButton(
                left, y, third, 20,
                "Emotes " + onOff(config.showEmotes()),
                () -> toggleEmotes()
        ));
        buttons.add(new RoundedButton(
                left + third + 4, y, third, 20,
                "Badges " + onOff(config.showBadges()),
                () -> toggleBadges()
        ));
        buttons.add(new RoundedButton(
                left + 2 * (third + 4), y, third, 20,
                "Time " + onOff(config.showTimestamps()),
                () -> toggleTime()
        ));
        y += 25;

        buttons.add(new RoundedButton(
                left, y, contentW, 20,
                "Compact Mode " + onOff(config.compactMode()),
                () -> toggleCompactMode()
        ));
        y += 25;

        sliders.add(new RoundedSlider(
                left, y, contentW, 16,
                20, 300, config.maxMessages(),
                value -> "History: "
                        + Math.round(value),
                value -> {
                    config.maxMessages(
                            (int) Math.round(value)
                    );
                    markDirty();
                }
        ));
        y += 22;

        sliders.add(new RoundedSlider(
                left, y, contentW, 16,
                0, 120, config.fadeAfterSeconds(),
                value -> value < 1
                        ? "Fade: Never"
                        : "Fade: "
                                + Math.round(value)
                                + "s",
                value -> {
                    config.fadeAfterSeconds(
                            (int) Math.round(value)
                    );
                    markDirty();
                }
        ));
    }

    private void initWidgets(
            int left,
            int y,
            int contentW
    ) {
        if (widgetStyleEditor) {
            initWidgetStyle(left, y, contentW);
            return;
        }

        addWidgetRow(
                left, y, contentW,
                "Viewers",
                config.showViewerWidget(),
                () -> config.showViewerWidget(
                        !config.showViewerWidget()
                ),
                "viewer"
        );
        y += 22;
        addWidgetRow(
                left, y, contentW,
                "Title",
                config.showTitleWidget(),
                () -> config.showTitleWidget(
                        !config.showTitleWidget()
                ),
                "title"
        );
        y += 22;
        addWidgetRow(
                left, y, contentW,
                "Hype Train",
                config.showHypeTrainWidget(),
                () -> config.showHypeTrainWidget(
                        !config.showHypeTrainWidget()
                ),
                "hype"
        );
        y += 22;
addWidgetRow(
                left, y, contentW,
                "Followers",
                config.showFollowerWidget(),
                () -> config.showFollowerWidget(
                        !config.showFollowerWidget()
                ),
                "follower"
        );
        y += 22;
        addWidgetRow(
                left, y, contentW,
                "Creator goals",
                config.showCreatorGoals(),
                () -> config.showCreatorGoals(
                        !config.showCreatorGoals()
                ),
                "goal:*"
        );
        y += 24;

        if (session.eventAuthorizationNeeded()) {
            buttons.add(new RoundedButton(
                    left,
                    y,
                    contentW,
                    18,
                    "Authorize Twitch events",
                    this::authorizeEvents
            ));
            y += 20;
        } else if (config.showCreatorGoals()
                && !session.creatorGoalsAuthorized()) {
            buttons.add(new RoundedButton(
                    left,
                    y,
                    contentW,
                    18,
                    "Authorize channel owner for goals",
                    this::forceAuthorizeEvents
            ));
            y += 20;
        }

        buttons.add(new RoundedButton(
                left,
                y,
                contentW,
                18,
                "Refresh widget data",
                session::refreshLiveData
        ));
    }

    private void addWidgetRow(
            int left,
            int y,
            int contentW,
            String label,
            boolean enabled,
            Runnable toggle,
            String key
    ) {
        int settingsWidth = 86;
        int toggleWidth =
                contentW - settingsWidth - 4;

        buttons.add(new RoundedButton(
                left,
                y,
                toggleWidth,
                20,
                label + " " + onOff(enabled),
                () -> {
                    toggle.run();
                    persist();
                    clearAndInit();
                }
        ));
        buttons.add(new RoundedButton(
                left + toggleWidth + 4,
                y,
                settingsWidth,
                20,
                "Settings",
                () -> {
                    selectedWidgetKey = key;
                    widgetStyleEditor = true;
                    clearAndInit();
                }
        ));
    }

    private void initWidgetStyle(
            int left,
            int y,
            int contentW
    ) {
        buttons.add(new RoundedButton(
                left,
                y,
                contentW,
                18,
                "< Back to widgets",
                () -> {
                    widgetStyleEditor = false;
                    clearAndInit();
                }
        ));
        y += 22;

        List<String> keys = widgetStyleKeys();
        if (!keys.contains(selectedWidgetKey)) {
            selectedWidgetKey = "viewer";
        }

        int selectorSide = 28;
        buttons.add(new RoundedButton(
                left,
                y,
                selectorSide,
                20,
                "<",
                () -> cycleWidgetStyle(-1)
        ));
        buttons.add(new RoundedButton(
                left + selectorSide + 4,
                y,
                contentW - selectorSide * 2 - 8,
                20,
                widgetDisplayName(selectedWidgetKey),
                () -> {
                }
        ));
        buttons.add(new RoundedButton(
                left + contentW - selectorSide,
                y,
                selectorSide,
                20,
                ">",
                () -> cycleWidgetStyle(1)
        ));
        y += 24;

        TwitchHudConfig.WidgetStyle style =
                config.widgetStyle(selectedWidgetKey);

        sliders.add(new RoundedSlider(
                left, y, contentW, 16,
                0.0, 1.0, style.backgroundOpacity(),
                value -> "Background: "
                        + Math.round(value * 100) + "%",
                value -> {
                    config.widgetStyle(selectedWidgetKey)
                            .backgroundOpacity(value);
                    markDirty();
                }
        ));
        y += 22;

        sliders.add(new RoundedSlider(
                left, y, contentW, 16,
                0.5, 2.0, style.textScale(),
                value -> "Text size: "
                        + String.format("%.1fx", value),
                value -> {
                    config.widgetStyle(selectedWidgetKey)
                            .textScale(value);
                    markDirty();
                }
        ));
        y += 22;

        sliders.add(new RoundedSlider(
                left, y, contentW, 16,
                0.2, 1.0, style.textOpacity(),
                value -> "Text opacity: "
                        + Math.round(value * 100) + "%",
                value -> {
                    config.widgetStyle(selectedWidgetKey)
                            .textOpacity(value);
                    markDirty();
                }
        ));
        y += 22;

        widgetColorField = new TextFieldWidget(
                textRenderer,
                left + 4,
                y + 2,
                64,
                18,
                Text.literal("Widget color")
        );
        widgetColorField.setDrawsBackground(false);
        widgetColorField.setMaxLength(6);
        widgetColorField.setText(
                String.format(
                        "%06X",
                        style.textColor()
                )
        );
        widgetColorField.setChangedListener(
                this::onWidgetColorFieldChanged
        );
        addDrawableChild(widgetColorField);

        buttons.add(new RoundedButton(
                left + 72,
                y,
                104,
                20,
                "Font: " + widgetFontLabel(),
                this::cycleWidgetFont
        ));
        buttons.add(new RoundedButton(
                left + 180,
                y,
                contentW - 180,
                20,
                "Color",
                this::cycleWidgetColor
        ));
        y += 25;

        int half = (contentW - 4) / 2;
        buttons.add(new RoundedButton(
                left,
                y,
                half,
                20,
                "Copy chat style",
                () -> {
                    config.copyChatStyleToWidget(
                            selectedWidgetKey
                    );
                    persist();
                    clearAndInit();
                }
        ));
        buttons.add(new RoundedButton(
                left + half + 4,
                y,
                half,
                20,
                "Reset style",
                () -> {
                    config.resetWidgetStyle(
                            selectedWidgetKey
                    );
                    persist();
                    clearAndInit();
                }
        ));
        y += 24;
    }

    private List<String> widgetStyleKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("viewer");
        keys.add("title");
        keys.add("hype");
        keys.add("follower");
        keys.add("goal:*");

        for (var goal : session.liveSnapshot().goals()) {
            String goalKey = goal.id().isBlank()
                    ? goal.type()
                    : goal.id();
            String key = "goal:" + goalKey;
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private void cycleWidgetStyle(int direction) {
        List<String> keys = widgetStyleKeys();
        int index = Math.max(
                0,
                keys.indexOf(selectedWidgetKey)
        );
        selectedWidgetKey = keys.get(
                Math.floorMod(
                        index + direction,
                        keys.size()
                )
        );
        clearAndInit();
    }

    private String widgetDisplayName(String key) {
        String label = switch (key) {
            case "viewer" -> "Viewer";
            case "title" -> "Live title";
            case "hype" -> "Hype Train";
            case "follower" -> "Followers";
            case "goal:*" -> "Creator goals default";
            default -> goalDisplayName(key);
        };
        if (label.length() > 25) {
            return label.substring(0, 22) + "...";
        }
        return label;
    }

    private String goalDisplayName(String key) {
        if (!key.startsWith("goal:")) {
            return key;
        }
        String id = key.substring(5);
        for (var goal : session.liveSnapshot().goals()) {
            String goalKey = goal.id().isBlank()
                    ? goal.type()
                    : goal.id();
            if (goalKey.equals(id)) {
                String name = goal.description().isBlank()
                        ? goal.type()
                        : goal.description();
                return "Goal: " + name;
            }
        }
        return "Creator goal";
    }

    private String widgetFontLabel() {
        String fontId = config.widgetStyle(
                selectedWidgetKey
        ).fontId();
        for (int i = 0; i < FONT_IDS.length; i++) {
            if (FONT_IDS[i].equals(fontId)) {
                return FONT_LABELS[i];
            }
        }
        return FONT_LABELS[0];
    }

    private void cycleWidgetFont() {
        TwitchHudConfig.WidgetStyle style =
                config.widgetStyle(selectedWidgetKey);
        int index = 0;
        for (int i = 0; i < FONT_IDS.length; i++) {
            if (FONT_IDS[i].equals(style.fontId())) {
                index = i;
                break;
            }
        }
        style.fontId(
                FONT_IDS[(index + 1) % FONT_IDS.length]
        );
        persist();
        clearAndInit();
    }

    private void cycleWidgetColor() {
        TwitchHudConfig.WidgetStyle style =
                config.widgetStyle(selectedWidgetKey);
        int current = style.textColor();
        int index = 0;
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i] == current) {
                index = i + 1;
                break;
            }
        }
        int color = PALETTE[index % PALETTE.length];
        style.textColor(color);
        if (widgetColorField != null) {
            widgetColorField.setText(
                    String.format("%06X", color)
            );
        }
        persist();
    }

    private void onWidgetColorFieldChanged(String value) {
        String text = value.trim();
        if (!text.matches("[0-9A-Fa-f]{6}")) {
            return;
        }
        config.widgetStyle(selectedWidgetKey)
                .textColor(
                        Integer.parseInt(text, 16)
                );
        markDirty();
    }

    private void initFilters(
            int left,
            int y,
            int contentW
    ) {
        int third = third(contentW);
        addToggle(
                left,
                y,
                third,
                "Bots",
                config.filterBots(),
                () -> config.filterBots(
                        !config.filterBots()
                )
        );
        addToggle(
                left + third + 4,
                y,
                third,
                "Spam",
                config.filterSpam(),
                () -> config.filterSpam(
                        !config.filterSpam()
                )
        );
        addToggle(
                left + 2 * (third + 4),
                y,
                third,
                "Words",
                config.filterBadWords(),
                () -> config.filterBadWords(
                        !config.filterBadWords()
                )
        );
        y += 26;

        newWordField = new TextFieldWidget(
                textRenderer,
                left + 4,
                y + 2,
                contentW - 88,
                18,
                Text.literal("Blocked word")
        );
        newWordField.setDrawsBackground(false);
        newWordField.setMaxLength(32);
        addDrawableChild(newWordField);
        buttons.add(new RoundedButton(
                left + contentW - 78,
                y,
                78,
                20,
                "Add",
                () -> {
                    config.addBlockedWord(
                            newWordField.getText()
                    );
                    newWordField.setText("");
                    persist();
                    clearAndInit();
                }
        ));
        y += 25;

        int bottom = panelY + panelH - 30;
        for (String word : new ArrayList<>(
                config.blockedWords()
        )) {
            if (y + 18 > bottom) {
                break;
            }
            buttons.add(new RoundedButton(
                    left,
                    y,
                    contentW,
                    17,
                    "Remove word: " + word,
                    () -> {
                        config.removeBlockedWord(word);
                        persist();
                        clearAndInit();
                    }
            ));
            y += 19;
        }

        for (String hidden : new ArrayList<>(
                config.hiddenUsers()
        )) {
            if (y + 18 > bottom) {
                break;
            }
            buttons.add(new RoundedButton(
                    left,
                    y,
                    contentW,
                    17,
                    "Unhide: " + hidden,
                    () -> {
                        config.unhideUser(hidden);
                        persist();
                        clearAndInit();
                    }
            ));
            y += 19;
        }
    }

    private void initProfiles(
            int left,
            int y,
            int contentW
    ) {
        profileField = new TextFieldWidget(
                textRenderer,
                left + 4,
                y + 2,
                contentW - 8,
                18,
                Text.literal("Profile name")
        );
        profileField.setDrawsBackground(false);
        profileField.setMaxLength(40);
        addDrawableChild(profileField);
        y += 26;

        int third = third(contentW);
        buttons.add(new RoundedButton(
                left,
                y,
                third,
                20,
                "Save",
                this::saveProfile
        ));
        buttons.add(new RoundedButton(
                left + third + 4,
                y,
                third,
                20,
                "Load",
                this::loadProfile
        ));
        buttons.add(new RoundedButton(
                left + 2 * (third + 4),
                y,
                third,
                20,
                "Delete",
                this::deleteProfile
        ));
        y += 26;

        int half = (contentW - 4) / 2;
        buttons.add(new RoundedButton(
                left,
                y,
                half,
                20,
                "Export settings",
                this::exportSettings
        ));
        buttons.add(new RoundedButton(
                left + half + 4,
                y,
                half,
                20,
                "Import settings",
                this::importSettings
        ));
    }

    private void addToggle(
            int x,
            int y,
            int width,
            String label,
            boolean value,
            Runnable change
    ) {
        buttons.add(new RoundedButton(
                x,
                y,
                width,
                20,
                label + " " + onOff(value),
                () -> {
                    change.run();
                    persist();
                    clearAndInit();
                }
        ));
    }

    private void connectChannel() {
        String channel = channelField == null
                ? config.channel()
                : channelField.getText().trim();
        if (session.eventAuthorizationNeeded()) {
            authorizeEvents();
        }
        session.restart(channel);
        persist();
    }

    private void authorizeEvents() {
        authorizeEvents(false);
    }

    private void forceAuthorizeEvents() {
        authorizeEvents(true);
    }

    private void authorizeEvents(boolean force) {
        if (!force && session.eventAlertsConnected()) {
            actionStatus = "Twitch events connected";
            return;
        }

        String existing = session.eventAuthorizationUrl();
        if (existing != null && !existing.isBlank()) {
            PlatformImpl.INSTANCE.openUrl(existing);
            actionStatus = "Authorization page opened";
            return;
        }

        session.beginEventAuthorization()
                .thenAccept(authorization -> {
                    if (authorization == null) {
                        return;
                    }
                    MinecraftClient.getInstance().execute(() -> {
                        if (authorization.userCode() != null
                                && !authorization.userCode()
                                        .isBlank()) {
                            PlatformImpl.INSTANCE.copyToClipboard(
                                    authorization.userCode()
                            );
                        }
                        PlatformImpl.INSTANCE.openUrl(
                                authorization.verificationUri()
                        );
                        actionStatus =
                                "Code copied - finish Twitch authorization";
                    });
                });
    }

    private void saveProfile() {
        if (profileField == null) {
            return;
        }
        actionStatus = profileStore.saveProfile(
                profileField.getText(),
                config
        ) ? "Profile saved" : "Profile name required";
    }

    private void loadProfile() {
        if (profileField == null) {
            return;
        }
        Optional<TwitchHudConfig> loaded =
                profileStore.loadProfile(
                        profileField.getText()
                );
        if (loaded.isEmpty()) {
            actionStatus = "Profile not found";
            return;
        }
        applyConfig(loaded.get());
        actionStatus = "Profile loaded";
    }

    private void deleteProfile() {
        if (profileField == null) {
            return;
        }
        actionStatus = profileStore.deleteProfile(
                profileField.getText()
        ) ? "Profile deleted" : "Profile not found";
    }

    private void exportSettings() {
        if (profileStore.exportConfig(config)) {
            PlatformImpl.INSTANCE.copyToClipboard(
                    profileStore.exportPath()
                            .toAbsolutePath()
                            .toString()
            );
            actionStatus =
                    "Exported - path copied";
        } else {
            actionStatus = "Export failed";
        }
    }

    private void importSettings() {
        Optional<TwitchHudConfig> imported =
                profileStore.importConfig();
        if (imported.isEmpty()) {
            actionStatus =
                    "Put twitchhud-export.json in config";
            return;
        }
        applyConfig(imported.get());
        actionStatus = "Settings imported";
    }

    private void applyConfig(TwitchHudConfig loaded) {
        config.copyFrom(loaded);
        persist();
        session.restart(config.channel());
        session.refreshLiveData();
        clearAndInit();
    }

    private void markDirty() {
        settingsDirty = true;
    }

    private void persist() {
        config.save(configPath);
        settingsDirty = false;
    }

    private void toggleEmotes() {
        config.showEmotes(!config.showEmotes());
        persist();
        clearAndInit();
    }

    private void toggleBadges() {
        config.showBadges(!config.showBadges());
        persist();
        clearAndInit();
    }

    private void toggleTime() {
        config.showTimestamps(!config.showTimestamps());
        persist();
        clearAndInit();
    }

    private void toggleCompactMode() {
        config.compactMode(!config.compactMode());
        persist();
        clearAndInit();
    }

    private String currentFontLabel() {
        for (int i = 0; i < FONT_IDS.length; i++) {
            if (FONT_IDS[i].equals(config.fontId())) {
                return FONT_LABELS[i];
            }
        }
        return FONT_LABELS[0];
    }

    private void cycleFont() {
        int index = 0;
        for (int i = 0; i < FONT_IDS.length; i++) {
            if (FONT_IDS[i].equals(config.fontId())) {
                index = i;
                break;
            }
        }
        config.fontId(
                FONT_IDS[(index + 1) % FONT_IDS.length]
        );
        persist();
        clearAndInit();
    }

    private void cycleColor() {
        int current = config.messageColor();
        int index = 0;
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i] == current) {
                index = i + 1;
                break;
            }
        }
        int color = PALETTE[index % PALETTE.length];
        config.messageColor(color);
        if (colorField != null) {
            colorField.setText(String.format("%06X", color));
        }
        persist();
    }

    private void onColorFieldChanged(String value) {
        String text = value.trim();
        if (!text.matches("[0-9A-Fa-f]{6}")) {
            return;
        }
        config.messageColor(
                Integer.parseInt(text, 16)
        );
        markDirty();
    }

    private static int third(int width) {
        return (width - 8) / 3;
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private static String statusLabel(
            ConnectionStatus status
    ) {
        return switch (status) {
            case CONNECTED -> "IRC connected";
            case CONNECTING -> "IRC connecting";
            case ERROR -> "IRC error";
            case DISCONNECTED -> "IRC disconnected";
        };
    }

    private int overlayX() {
        return (int) (
                config.x()
                        * PlatformImpl.INSTANCE.screenWidth()
        );
    }

    private int overlayY() {
        return (int) (
                config.y()
                        * PlatformImpl.INSTANCE.screenHeight()
        );
    }

    @Override
    public void render(
            DrawContext context,
            int mouseX,
            int mouseY,
            float delta
    ) {
        renderer.render(context, true, true);

        int x = overlayX();
        int y = overlayY();
        int w = (int) config.width();
        int h = (int) config.height();

        context.drawBorder(
                x,
                y,
                w,
                h,
                0xFFFFA500
        );
        context.fill(
                x + w - HANDLE_SIZE,
                y + h - HANDLE_SIZE,
                x + w,
                y + h,
                0xFFFFA500
        );

        context.fill(
                panelX,
                panelY,
                panelX + panelW,
                panelY + panelH,
                0xF0202025
        );

        for (RoundedButton button : buttons) {
            button.render(context, mouseX, mouseY);
        }
        for (RoundedSlider slider : sliders) {
            slider.render(context, mouseX, mouseY);
        }

        String status = statusLabel(
                session.connectionStatus()
        );
        context.drawText(
                textRenderer,
                status,
                panelX + 10,
                panelY + panelH - 20,
                0xFF9E9EA9,
                false
        );

        if (!actionStatus.isBlank()) {
            int maxWidth = panelW - 92;
            String display = actionStatus;
            while (textRenderer.getWidth(display) > maxWidth
                    && display.length() > 4) {
                display = display.substring(
                        0,
                        display.length() - 4
                ) + "...";
            }
            context.drawText(
                    textRenderer,
                    display,
                    panelX + 10,
                    panelY + panelH - 32,
                    0xFFCFCFD7,
                    false
            );
        }

        if (tab == Tab.WIDGETS) {
            context.drawText(
                    textRenderer,
                    widgetStyleEditor
                            ? "Widget style"
                            : "Drag widgets; orange corner resizes them",
                    panelX + 10,
                    panelY + panelH - 44,
                    0xFFB9B9C5,
                    false
            );
        }

        if (tab == Tab.PROFILES) {
            List<String> profiles = profileStore.profiles();
            String names = profiles.isEmpty()
                    ? "Profiles: none"
                    : "Profiles: "
                            + String.join(", ", profiles);
            context.drawText(
                    textRenderer,
                    names,
                    panelX + 10,
                    panelY + 132,
                    0xFFB9B9C5,
                    false
            );
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(
            double mouseX,
            double mouseY,
            int button
    ) {
        for (RoundedSlider slider : sliders) {
            if (slider.mouseClicked(
                    mouseX,
                    mouseY,
                    button
            )) {
                return true;
            }
        }

        for (RoundedButton rounded :
                new ArrayList<>(buttons)) {
            if (rounded.mouseClicked(
                    mouseX,
                    mouseY,
                    button
            )) {
                return true;
            }
        }

        if (button == 0 && tab == Tab.WIDGETS) {
            Optional<ChatOverlayRenderer.WidgetHit> hit =
                    renderer.widgetAt(mouseX, mouseY);
            if (hit.isPresent()) {
                ChatOverlayRenderer.WidgetHit widget =
                        hit.get();
                selectedWidgetKey = widget.key();
                boolean resizeHandle =
                        mouseX >= widget.x()
                                + widget.width()
                                - HANDLE_SIZE
                                && mouseY >= widget.y()
                                + widget.height()
                                - HANDLE_SIZE;
                if (resizeHandle) {
                    resizingWidget = widget.key();
                    widgetResizeX = widget.x();
                    widgetResizeY = widget.y();
                    widgetDefaultWidth = widget.width();
                    widgetDefaultHeight = widget.height();
                    draggingWidget = null;
                } else {
                    draggingWidget = widget.key();
                    dragOffsetX = mouseX - widget.x();
                    dragOffsetY = mouseY - widget.y();
                    resizingWidget = null;
                }
                return true;
            }
        }

        if (button == 0 && !config.locked()) {
            int x = overlayX();
            int y = overlayY();
            int w = (int) config.width();
            int h = (int) config.height();

            if (mouseX >= x + w - HANDLE_SIZE
                    && mouseX <= x + w
                    && mouseY >= y + h - HANDLE_SIZE
                    && mouseY <= y + h) {
                resizingOverlay = true;
                return true;
            }
            if (mouseX >= x
                    && mouseX <= x + w
                    && mouseY >= y
                    && mouseY <= y + h) {
                draggingOverlay = true;
                dragOffsetX = mouseX - x;
                dragOffsetY = mouseY - y;
                return true;
            }
        }

        return super.mouseClicked(
                mouseX,
                mouseY,
                button
        );
    }

    @Override
    public boolean mouseDragged(
            double mouseX,
            double mouseY,
            int button,
            double deltaX,
            double deltaY
    ) {
        for (RoundedSlider slider : sliders) {
            if (slider.mouseDragged(mouseX)) {
                return true;
            }
        }

        if (resizingWidget != null) {
            int screenW =
                    PlatformImpl.INSTANCE.screenWidth();
            int screenH =
                    PlatformImpl.INSTANCE.screenHeight();
            double nextWidth = Math.min(
                    screenW - widgetResizeX,
                    mouseX - widgetResizeX
            );
            double nextHeight = Math.min(
                    screenH - widgetResizeY,
                    mouseY - widgetResizeY
            );
            config.resizeWidget(
                    resizingWidget,
                    nextWidth,
                    nextHeight,
                    widgetDefaultWidth,
                    widgetDefaultHeight
            );
            return true;
        }

        if (draggingWidget != null) {
            int screenW =
                    PlatformImpl.INSTANCE.screenWidth();
            int screenH =
                    PlatformImpl.INSTANCE.screenHeight();
            config.moveWidget(
                    draggingWidget,
                    (mouseX - dragOffsetX) / screenW,
                    (mouseY - dragOffsetY) / screenH
            );
            return true;
        }

        if (draggingOverlay) {
            int screenW =
                    PlatformImpl.INSTANCE.screenWidth();
            int screenH =
                    PlatformImpl.INSTANCE.screenHeight();
            config.x(
                    (mouseX - dragOffsetX) / screenW
            );
            config.y(
                    (mouseY - dragOffsetY) / screenH
            );
            return true;
        }

        if (resizingOverlay) {
            config.width(
                    Math.max(120, mouseX - overlayX())
            );
            config.height(
                    Math.max(60, mouseY - overlayY())
            );
            return true;
        }

        return super.mouseDragged(
                mouseX,
                mouseY,
                button,
                deltaX,
                deltaY
        );
    }

    @Override
    public boolean mouseReleased(
            double mouseX,
            double mouseY,
            int button
    ) {
        for (RoundedSlider slider : sliders) {
            slider.mouseReleased();
        }
        if (settingsDirty
                || draggingOverlay
                || resizingOverlay
                || draggingWidget != null
                || resizingWidget != null) {
            persist();
        }
        boolean widgetInteraction =
                draggingWidget != null
                        || resizingWidget != null;
        draggingOverlay = false;
        resizingOverlay = false;
        draggingWidget = null;
        resizingWidget = null;
        if (widgetInteraction
                && tab == Tab.WIDGETS
                && widgetStyleEditor) {
            clearAndInit();
        }
        return super.mouseReleased(
                mouseX,
                mouseY,
                button
        );
    }

    @Override
    public void close() {
        persist();
        client.setScreen(parent);
    }
}
