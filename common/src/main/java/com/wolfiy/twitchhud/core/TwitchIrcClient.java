package com.wolfiy.twitchhud.core;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class TwitchIrcClient {
    private static final URI ENDPOINT =
            URI.create("wss://irc-ws.chat.twitch.tv:443");
    private static final long RECONNECT_DELAY_MS = 5_000L;

    private final AtomicBoolean connected =
            new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled =
            new AtomicBoolean();
    private final AtomicInteger connectionEpoch =
            new AtomicInteger();
    private final Listener listener;

    private volatile WebSocket socket;
    private volatile String channel = "";
    private volatile boolean shouldRun;
    private volatile Thread reconnectThread;

    public TwitchIrcClient(Listener listener) {
        this.listener = listener;
    }

    public interface Listener {
        void onMessage(ChatMessage message);

        void onClearChat(String username);

        void onDeleteMessage(String messageId);

        void onConnectionStateChanged(boolean connected);

        void onConnectionStatusChanged(ConnectionStatus status);

        void onRoomId(String roomId);
    }

    public void connect(String channelName) {
        String nextChannel = normalizeChannel(channelName);
        if (nextChannel.isBlank()) {
            disconnect();
            return;
        }

        shouldRun = true;
        channel = nextChannel;
        cancelReconnect();
        closeSocket();
        listener.onConnectionStatusChanged(
                ConnectionStatus.CONNECTING
        );
        openSocket();
    }

    public void disconnect() {
        shouldRun = false;
        cancelReconnect();
        closeSocket();
        setConnected(false);
        listener.onConnectionStatusChanged(
                ConnectionStatus.DISCONNECTED
        );
    }

    public boolean isConnected() {
        return connected.get();
    }

    private void openSocket() {
        if (!shouldRun || channel.isBlank()) {
            return;
        }

        String requestedChannel = channel;
        String anonymousUser = "justinfan"
                + ThreadLocalRandom.current()
                        .nextInt(10_000, 100_000);
        int epoch = connectionEpoch.incrementAndGet();
        StringBuilder incoming = new StringBuilder();

        TwitchHttp.client()
                .newWebSocketBuilder()
                .buildAsync(
                        ENDPOINT,
                        new WebSocket.Listener() {
                            @Override
                            public void onOpen(
                                    WebSocket webSocket
                            ) {
                                if (!isCurrent(
                                        epoch,
                                        requestedChannel
                                )) {
                                    webSocket.sendClose(
                                            WebSocket.NORMAL_CLOSURE,
                                            "stale"
                                    );
                                    return;
                                }

                                socket = webSocket;
                                webSocket.sendText(
                                        "CAP REQ :twitch.tv/tags "
                                                + "twitch.tv/commands",
                                        true
                                );
                                webSocket.sendText(
                                        "PASS SCHMOOPIIE",
                                        true
                                );
                                webSocket.sendText(
                                        "NICK " + anonymousUser,
                                        true
                                );
                                webSocket.sendText(
                                        "JOIN #" + requestedChannel,
                                        true
                                );
                                webSocket.request(1);
                            }

                            @Override
                            public CompletionStage<?> onText(
                                    WebSocket webSocket,
                                    CharSequence data,
                                    boolean last
                            ) {
                                if (!isCurrent(
                                        epoch,
                                        requestedChannel
                                )) {
                                    webSocket.request(1);
                                    return null;
                                }

                                incoming.append(data);
                                if (last) {
                                    String full =
                                            incoming.toString();
                                    incoming.setLength(0);
                                    for (String line
                                            : full.split("\r\n")) {
                                        if (!line.isBlank()) {
                                            handleLine(
                                                    webSocket,
                                                    line
                                            );
                                        }
                                    }
                                }
                                webSocket.request(1);
                                return null;
                            }

                            @Override
                            public CompletionStage<?> onClose(
                                    WebSocket webSocket,
                                    int statusCode,
                                    String reason
                            ) {
                                if (isCurrent(
                                        epoch,
                                        requestedChannel
                                )) {
                                    setConnected(false);
                                    listener.onConnectionStatusChanged(
                                            shouldRun
                                                    ? ConnectionStatus.ERROR
                                                    : ConnectionStatus.DISCONNECTED
                                    );
                                    scheduleReconnect(
                                            RECONNECT_DELAY_MS,
                                            epoch
                                    );
                                }
                                return null;
                            }

                            @Override
                            public void onError(
                                    WebSocket webSocket,
                                    Throwable error
                            ) {
                                if (isCurrent(
                                        epoch,
                                        requestedChannel
                                )) {
                                    setConnected(false);
                                    listener.onConnectionStatusChanged(
                                            shouldRun
                                                    ? ConnectionStatus.ERROR
                                                    : ConnectionStatus.DISCONNECTED
                                    );
                                    scheduleReconnect(
                                            RECONNECT_DELAY_MS,
                                            epoch
                                    );
                                }
                            }
                        }
                )
                .exceptionally(error -> {
                    if (isCurrent(epoch, requestedChannel)) {
                        setConnected(false);
                        listener.onConnectionStatusChanged(
                                ConnectionStatus.ERROR
                        );
                        scheduleReconnect(
                                RECONNECT_DELAY_MS,
                                epoch
                        );
                    }
                    return null;
                });
    }

    private boolean isCurrent(
            int epoch,
            String requestedChannel
    ) {
        return shouldRun
                && epoch == connectionEpoch.get()
                && requestedChannel.equals(channel);
    }

    private void scheduleReconnect(
            long delayMillis,
            int scheduledEpoch
    ) {
        if (!shouldRun
                || scheduledEpoch != connectionEpoch.get()
                || !reconnectScheduled.compareAndSet(
                        false,
                        true
                )) {
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(Math.max(0L, delayMillis));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                reconnectScheduled.set(false);
            }

            if (shouldRun
                    && scheduledEpoch
                    == connectionEpoch.get()) {
                listener.onConnectionStatusChanged(
                        ConnectionStatus.CONNECTING
                );
                openSocket();
            }
        }, "twitchhud-irc-reconnect");
        thread.setDaemon(true);
        reconnectThread = thread;
        thread.start();
    }

    private void cancelReconnect() {
        reconnectScheduled.set(false);
        Thread thread = reconnectThread;
        reconnectThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void closeSocket() {
        connectionEpoch.incrementAndGet();
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            current.sendClose(
                    WebSocket.NORMAL_CLOSURE,
                    "closed"
            );
        }
    }

    private void markJoined() {
        if (connected.compareAndSet(false, true)) {
            listener.onConnectionStateChanged(true);
            listener.onConnectionStatusChanged(
                    ConnectionStatus.CONNECTED
            );
        }
    }

    private void setConnected(boolean value) {
        boolean previous = connected.getAndSet(value);
        if (previous != value) {
            listener.onConnectionStateChanged(value);
        }
    }

    private void handleLine(
            WebSocket webSocket,
            String line
    ) {
        if (line.startsWith("PING")) {
            webSocket.sendText(
                    "PONG :tmi.twitch.tv",
                    true
            );
            return;
        }
        if (line.contains(" RECONNECT")) {
            int epoch = connectionEpoch.get();
            setConnected(false);
            listener.onConnectionStatusChanged(
                    ConnectionStatus.CONNECTING
            );
            webSocket.sendClose(
                    WebSocket.NORMAL_CLOSURE,
                    "reconnect"
            );
            scheduleReconnect(0L, epoch);
            return;
        }

        Map<String, String> tags = new HashMap<>();
        String rest = line;
        if (rest.startsWith("@")) {
            int space = rest.indexOf(' ');
            if (space <= 1) {
                return;
            }
            String tagPart = rest.substring(1, space);
            rest = rest.substring(space + 1);
            for (String pair : tagPart.split(";")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    tags.put(
                            pair.substring(0, equals),
                            unescapeTag(
                                    pair.substring(equals + 1)
                            )
                    );
                }
            }
        }

        if (rest.contains("ROOMSTATE")) {
            notifyRoomId(tags);
            markJoined();
        } else if (rest.contains("PRIVMSG")) {
            markJoined();
            handlePrivmsg(tags, rest);
        } else if (rest.contains("USERNOTICE")) {
            markJoined();
            handleUsernotice(tags, rest);
        } else if (rest.contains("CLEARCHAT")) {
            int hash = rest.lastIndexOf('#');
            int colon = rest.indexOf(':', hash);
            if (colon > 0) {
                listener.onClearChat(
                        rest.substring(colon + 1)
                                .trim()
                                .toLowerCase(Locale.ROOT)
                );
            }
        } else if (rest.contains(" NOTICE ")) {
            handleNotice(tags);
        } else if (rest.contains("CLEARMSG")) {
            String targetId = tags.get("target-msg-id");
            if (targetId != null && !targetId.isBlank()) {
                listener.onDeleteMessage(targetId);
            }
        }
    }

    private void handleNotice(
            Map<String, String> tags
    ) {
        String msgId = tags.getOrDefault("msg-id", "");
        if (!"msg_channel_suspended".equals(msgId)
                && !"msg_banned".equals(msgId)
                && !"msg_channel_blocked".equals(msgId)) {
            return;
        }

        shouldRun = false;
        cancelReconnect();
        setConnected(false);
        listener.onConnectionStatusChanged(
                ConnectionStatus.ERROR
        );
        closeSocket();
    }

    private void handlePrivmsg(
            Map<String, String> tags,
            String rest
    ) {
        int colon = rest.indexOf(" :");
        if (colon < 0) {
            return;
        }

        String text = normalizeAction(
                rest.substring(colon + 2)
        );
        String username = usernameFromPrefix(
                rest,
                tags
        );
        String displayName = tags.getOrDefault(
                "display-name",
                username
        );

        List<ChatMessage.Badge> badges =
                parseBadges(tags);
        boolean broadcaster =
                hasBadge(badges, "broadcaster");
        boolean moderator =
                hasBadge(badges, "moderator")
                        || "1".equals(tags.get("mod"));
        boolean subscriber =
                hasBadge(badges, "subscriber")
                        || "1".equals(tags.get("subscriber"));
        int color = parseColor(tags, username);
        List<ChatMessage.EmoteRef> emotes =
                parseEmotes(tags, text);

        String id = tags.getOrDefault(
                "id",
                username
                        + tags.getOrDefault(
                                "tmi-sent-ts",
                                String.valueOf(
                                        System.currentTimeMillis()
                                )
                        )
        );

        String bits = tags.get("bits");
        ChatMessage.Kind kind = ChatMessage.Kind.CHAT;
        String systemText = null;

        if (bits != null && !bits.isBlank()) {
            kind = ChatMessage.Kind.CHEER;
            systemText = displayName
                    + " cheered "
                    + bits
                    + " bits"
                    + (text.isBlank()
                            ? ""
                            : ": " + text);
        }

        notifyRoomId(tags);
        listener.onMessage(new ChatMessage(
                id,
                username,
                displayName,
                text,
                color,
                broadcaster,
                moderator,
                subscriber,
                emotes,
                badges,
                System.currentTimeMillis(),
                kind,
                systemText
        ));
    }

    private void handleUsernotice(
            Map<String, String> tags,
            String rest
    ) {
        String username = usernameFromPrefix(
                rest,
                tags
        );
        String displayName = tags.getOrDefault(
                "display-name",
                username
        );
        List<ChatMessage.Badge> badges =
                parseBadges(tags);
        int color = parseColor(tags, username);

        int colon = rest.indexOf(" :");
        String text = colon >= 0
                ? normalizeAction(rest.substring(colon + 2))
                : "";
        List<ChatMessage.EmoteRef> emotes =
                parseEmotes(tags, text);

        String msgId = tags.getOrDefault("msg-id", "");
        ChatMessage.Kind kind = switch (msgId) {
            case "sub" -> ChatMessage.Kind.SUB;
            case "resub" -> ChatMessage.Kind.RESUB;
            case "subgift", "submysterygift",
                    "giftpaidupgrade",
                    "anongiftpaidupgrade",
                    "rewardgift" ->
                    ChatMessage.Kind.SUBGIFT;
            case "raid", "unraid" ->
                    ChatMessage.Kind.RAID;
            case "bitsbadgetier" ->
                    ChatMessage.Kind.CHEER;
            case "announcement" ->
                    ChatMessage.Kind.ANNOUNCEMENT;
            default -> ChatMessage.Kind.SYSTEM;
        };

        String systemText = tags.get("system-msg");
        String id = tags.getOrDefault(
                "id",
                "notice-" + System.nanoTime()
        );

        notifyRoomId(tags);
        listener.onMessage(new ChatMessage(
                id,
                username,
                displayName,
                text,
                color,
                hasBadge(badges, "broadcaster"),
                hasBadge(badges, "moderator"),
                hasBadge(badges, "subscriber"),
                emotes,
                badges,
                System.currentTimeMillis(),
                kind,
                systemText
        ));
    }

    private void notifyRoomId(
            Map<String, String> tags
    ) {
        String roomId = tags.get("room-id");
        if (roomId != null && !roomId.isBlank()) {
            listener.onRoomId(roomId);
        }
    }

    private static String usernameFromPrefix(
            String rest,
            Map<String, String> tags
    ) {
        int bang = rest.indexOf('!');
        if (bang > 0) {
            return rest.substring(1, bang)
                    .toLowerCase(Locale.ROOT);
        }
        return tags.getOrDefault(
                "display-name",
                ""
        ).toLowerCase(Locale.ROOT);
    }

    private static List<ChatMessage.Badge> parseBadges(
            Map<String, String> tags
    ) {
        List<ChatMessage.Badge> badges =
                new ArrayList<>();
        String badgeTag = tags.getOrDefault(
                "badges",
                ""
        );
        if (badgeTag.isEmpty()) {
            return badges;
        }

        for (String entry : badgeTag.split(",")) {
            int slash = entry.indexOf('/');
            if (slash > 0
                    && slash + 1 < entry.length()) {
                badges.add(new ChatMessage.Badge(
                        entry.substring(0, slash),
                        entry.substring(slash + 1)
                ));
            }
        }
        return badges;
    }

    private static boolean hasBadge(
            List<ChatMessage.Badge> badges,
            String setId
    ) {
        for (ChatMessage.Badge badge : badges) {
            if (badge.setId().equals(setId)) {
                return true;
            }
        }
        return false;
    }

    private static int parseColor(
            Map<String, String> tags,
            String username
    ) {
        return TwitchUserColor.resolve(
                tags.get("color"),
                username
        );
    }

    private static List<ChatMessage.EmoteRef> parseEmotes(
            Map<String, String> tags,
            String text
    ) {
        List<ChatMessage.EmoteRef> emotes =
                new ArrayList<>();
        String emoteTag = tags.get("emotes");
        if (emoteTag == null
                || emoteTag.isEmpty()
                || text.isEmpty()) {
            return emotes;
        }

        int codePointCount = text.codePointCount(
                0,
                text.length()
        );
        for (String emoteEntry : emoteTag.split("/")) {
            int colon = emoteEntry.indexOf(':');
            if (colon <= 0) {
                continue;
            }

            String emoteId =
                    emoteEntry.substring(0, colon);
            for (String range
                    : emoteEntry.substring(colon + 1)
                            .split(",")) {
                int dash = range.indexOf('-');
                if (dash <= 0) {
                    continue;
                }

                Integer startCodePoint = parseIndex(
                        range.substring(0, dash)
                );
                Integer endCodePoint = parseIndex(
                        range.substring(dash + 1)
                );
                if (startCodePoint == null
                        || endCodePoint == null
                        || startCodePoint < 0
                        || endCodePoint < startCodePoint
                        || endCodePoint >= codePointCount) {
                    continue;
                }

                int start = text.offsetByCodePoints(
                        0,
                        startCodePoint
                );
                int endExclusive = text.offsetByCodePoints(
                        0,
                        endCodePoint + 1
                );
                emotes.add(new ChatMessage.EmoteRef(
                        emoteId,
                        start,
                        endExclusive - 1
                ));
            }
        }
        return emotes;
    }

    private static Integer parseIndex(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String normalizeAction(String value) {
        if (value.startsWith("\u0001ACTION ")
                && value.endsWith("\u0001")
                && value.length() > 9) {
            return value.substring(
                    8,
                    value.length() - 1
            );
        }
        return value;
    }

    private static String normalizeChannel(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace("#", "");
    }

    private static String unescapeTag(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\\'
                    && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 's' -> out.append(' ');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case ':' -> out.append(';');
                    case '\\' -> out.append('\\');
                    default -> out.append(next);
                }
            } else {
                out.append(current);
            }
        }
        return out.toString();
    }
}
