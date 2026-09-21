package com.wolfiy.twitchhud.core;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

public final class TwitchHudSession
        implements TwitchIrcClient.Listener {
    private final TwitchHudConfig config;
    private final ModerationFilter filter;
    private final EmoteCache emoteCache = new EmoteCache();
    private final ThirdPartyEmoteService thirdPartyEmotes = new ThirdPartyEmoteService();
    private final TwitchIrcClient ircClient =
            new TwitchIrcClient(this);
    private final TwitchLiveState liveState =
            new TwitchLiveState();
    private final TwitchEventSubClient eventSubClient;
    private final TwitchChannelDataClient channelDataClient;
    private final Deque<ChatMessage> messages =
            new ArrayDeque<>();
    private final ReentrantLock lock =
            new ReentrantLock();
    private final BadgeIconService badgeIconService;

    private volatile boolean connected = false;
    private volatile int scrollOffset = 0;
    private volatile ConnectionStatus connectionStatus =
            ConnectionStatus.DISCONNECTED;

    public TwitchHudSession(
            TwitchHudConfig config,
            TwitchCredentials credentials,
            Path configDir
    ) {
        this.config = config;
        filter = new ModerationFilter(config);
        TwitchHelixClient helixClient =
                new TwitchHelixClient(credentials);
        badgeIconService = new BadgeIconService(
                helixClient
        );
        eventSubClient = new TwitchEventSubClient(
                credentials,
                configDir,
                this::onMessage,
                liveState
        );
        channelDataClient = new TwitchChannelDataClient(
                eventSubClient,
                helixClient,
                config,
                liveState,
                this::onMessage
        );
    }

    public void start() {
        channelDataClient.start();
        if (config.channel() != null
                && !config.channel().isBlank()) {
            ircClient.connect(config.channel());
            eventSubClient.start(config.channel());
            channelDataClient.refreshSoon();
        }
    }

    public void restart(String channel) {
        ircClient.disconnect();

        lock.lock();
        try {
            messages.clear();
        } finally {
            lock.unlock();
        }

        scrollOffset = 0;
        liveState.clearChannelData();
        thirdPartyEmotes.clearChannel();
        config.channel(channel);
        if (channel != null && !channel.isBlank()) {
            ircClient.connect(channel);
            eventSubClient.restart(channel);
            channelDataClient.refreshSoon();
        } else {
            eventSubClient.stop();
        }
    }

    public void stop() {
        ircClient.disconnect();
        eventSubClient.stop();
        channelDataClient.stop();
    }

    public boolean isConnected() {
        return connected;
    }

    public ConnectionStatus connectionStatus() {
        return connectionStatus;
    }

    public TwitchEventSubClient.Status eventStatus() {
        return eventSubClient.status();
    }

    public boolean eventAlertsConnected() {
        return eventSubClient.status()
                == TwitchEventSubClient.Status.CONNECTED
                && eventSubClient.fullyAuthorized();
    }

    public boolean eventAuthorizationNeeded() {
        if (eventSubClient.status()
                == TwitchEventSubClient.Status.DISABLED) {
            return false;
        }
        return !eventSubClient.fullyAuthorized();
    }

    public boolean creatorGoalsAuthorized() {
        return eventSubClient.creatorGoalsAuthorized();
    }

    public CompletableFuture<
            TwitchOAuthClient.DeviceAuthorization>
            beginEventAuthorization() {
        return eventSubClient.beginAuthorization();
    }

    public String eventAuthorizationUrl() {
        return eventSubClient.authorizationUrl();
    }

    public TwitchHudConfig config() {
        return config;
    }

    public EmoteCache emoteCache() {
        return emoteCache;
    }

    public ThirdPartyEmoteService thirdPartyEmotes() {
        return thirdPartyEmotes;
    }

    public BadgeIconService badgeIconService() {
        return badgeIconService;
    }

    public TwitchLiveState.Snapshot liveSnapshot() {
        return liveState.snapshot();
    }

    public void refreshLiveData() {
        channelDataClient.refreshSoon();
    }

    public List<ChatMessage> visibleMessages(
            boolean interactiveMode
    ) {
        lock.lock();
        try {
            List<ChatMessage> result =
                    new ArrayList<>();
            long now = System.currentTimeMillis();
            int fadeMs =
                    config.fadeAfterSeconds() * 1000;

            for (ChatMessage message : messages) {
                if (!message.hidden()) {
                    boolean faded = fadeMs > 0
                            && now - message.timestamp()
                            > fadeMs;
                    if (interactiveMode || !faded) {
                        result.add(message);
                    }
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    public int scrollOffset() {
        return scrollOffset;
    }

    public void scrollBy(int delta, int visibleCount) {
        int maxOffset = Math.max(
                0,
                visibleCount - 1
        );
        scrollOffset = Math.max(
                0,
                Math.min(
                        maxOffset,
                        scrollOffset + delta
                )
        );
    }

    public void resetScroll() {
        scrollOffset = 0;
    }

    public void hideUser(String username) {
        config.hideUser(username);
        lock.lock();
        try {
            for (ChatMessage message : messages) {
                if (message.username()
                        .equalsIgnoreCase(username)) {
                    message.setHidden(true);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onMessage(ChatMessage message) {
        if (filter.shouldHide(message)) {
            message.setHidden(true);
        }

        lock.lock();
        try {
            messages.addLast(message);
            while (messages.size()
                    > config.maxMessages()) {
                messages.removeFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onClearChat(String username) {
        lock.lock();
        try {
            for (ChatMessage message : messages) {
                if (message.username()
                        .equalsIgnoreCase(username)) {
                    message.setHidden(true);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onDeleteMessage(String messageId) {
        lock.lock();
        try {
            for (ChatMessage message : messages) {
                if (message.id().equals(messageId)) {
                    message.setHidden(true);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onConnectionStateChanged(
            boolean connected
    ) {
        this.connected = connected;
    }

    @Override
    public void onConnectionStatusChanged(
            ConnectionStatus status
    ) {
        connectionStatus = status;
    }

    @Override
    public void onRoomId(String roomId) {
        badgeIconService.ensureGlobalLoaded();
        badgeIconService.ensureChannelLoaded(roomId);
        thirdPartyEmotes.ensureGlobalLoaded();
        thirdPartyEmotes.ensureChannelLoaded(roomId, config.channel());
    }
}
