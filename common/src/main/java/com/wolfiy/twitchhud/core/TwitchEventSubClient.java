package com.wolfiy.twitchhud.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TwitchEventSubClient {
    public static final String REQUIRED_SCOPES =
            "channel:read:redemptions "
                    + "channel:read:hype_train "
                    + "channel:read:goals";

    public enum Status {
        DISABLED,
        DISCONNECTED,
        AUTHORIZING,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    public record AuthContext(
            String clientId,
            String accessToken,
            String userId,
            String broadcasterId,
            List<String> scopes
    ) {
        public boolean ready() {
            return clientId != null
                    && !clientId.isBlank()
                    && accessToken != null
                    && !accessToken.isBlank()
                    && broadcasterId != null
                    && !broadcasterId.isBlank();
        }

        public boolean hasScope(String scope) {
            return scope == null
                    || scope.isBlank()
                    || scopes.contains(scope);
        }

        public boolean ownsBroadcaster() {
            return userId != null
                    && !userId.isBlank()
                    && userId.equals(broadcasterId);
        }
    }

    private record SubscriptionSpec(
            String type,
            String version,
            String requiredScope
    ) {
    }

    private static final Logger LOGGER =
            Logger.getLogger("TwitchHUD");
    private static final URI EVENTSUB_URI = URI.create(
            "wss://eventsub.wss.twitch.tv/ws"
                    + "?keepalive_timeout_seconds=30"
    );
    private static final List<SubscriptionSpec> SUBSCRIPTIONS =
            List.of(
                    new SubscriptionSpec(
                            "channel.channel_points_custom_reward_redemption.add",
                            "1",
                            "channel:read:redemptions"
                    ),
                    new SubscriptionSpec(
                            "channel.goal.begin",
                            "1",
                            "channel:read:goals"
                    ),
                    new SubscriptionSpec(
                            "channel.goal.progress",
                            "1",
                            "channel:read:goals"
                    ),
                    new SubscriptionSpec(
                            "channel.goal.end",
                            "1",
                            "channel:read:goals"
                    ),
                    new SubscriptionSpec(
                            "channel.hype_train.begin",
                            "2",
                            "channel:read:hype_train"
                    ),
                    new SubscriptionSpec(
                            "channel.hype_train.progress",
                            "2",
                            "channel:read:hype_train"
                    ),
                    new SubscriptionSpec(
                            "channel.hype_train.end",
                            "2",
                            "channel:read:hype_train"
                    ),
                    new SubscriptionSpec(
                            "channel.update",
                            "2",
                            ""
                    ),
                    new SubscriptionSpec(
                            "stream.online",
                            "1",
                            ""
                    ),
                    new SubscriptionSpec(
                            "stream.offline",
                            "1",
                            ""
                    )
            );

    private final TwitchOAuthClient oauthClient;
    private final Consumer<ChatMessage> messageConsumer;
    private final TwitchLiveState liveState;
    private final AtomicInteger connectionEpoch =
            new AtomicInteger();
    private final AtomicBoolean reconnectScheduled =
            new AtomicBoolean();

    private volatile Status status = Status.DISCONNECTED;
    private volatile WebSocket socket;
    private volatile boolean shouldRun;
    private volatile String channel = "";
    private volatile String accessToken = "";
    private volatile String userId = "";
    private volatile String broadcasterId = "";
    private volatile List<String> userScopes = List.of();

    public TwitchEventSubClient(
            TwitchCredentials credentials,
            Path configDir,
            Consumer<ChatMessage> messageConsumer,
            TwitchLiveState liveState
    ) {
        this.messageConsumer = messageConsumer;
        this.liveState = liveState;
        oauthClient = new TwitchOAuthClient(
                credentials,
                configDir,
                this::onOAuthTokenReady
        );
        if (!oauthClient.isAvailable()) {
            status = Status.DISABLED;
        }
    }

    public Status status() {
        return status;
    }

    public String authorizationUrl() {
        return oauthClient.authorizationUrl();
    }

    public AuthContext authContext() {
        return new AuthContext(
                oauthClient.clientId(),
                accessToken,
                userId,
                broadcasterId,
                List.copyOf(userScopes)
        );
    }

    public boolean fullyAuthorized() {
        return channelOwnerAuthorized()
                && hasScopes(
                        userScopes,
                        REQUIRED_SCOPES
                );
    }

    public boolean creatorGoalsAuthorized() {
        return channelOwnerAuthorized()
                && hasScopes(
                        userScopes,
                        "channel:read:goals"
                );
    }

    public boolean channelOwnerAuthorized() {
        return !userId.isBlank()
                && userId.equals(broadcasterId);
    }

    public void start(String channelName) {
        channel = normalizeChannel(channelName);
        shouldRun = true;
        oauthClient.resume();

        if (!oauthClient.isAvailable()) {
            LOGGER.info(
                    "TwitchHUD: EventSub disabled - "
                            + "no Twitch client ID configured"
            );
            status = Status.DISABLED;
            return;
        }
        if (channel.isBlank()) {
            status = Status.DISCONNECTED;
            return;
        }
        if (!oauthClient.hasStoredToken()) {
            LOGGER.info(
                    "TwitchHUD: EventSub waiting for "
                            + "Twitch authorization"
            );
            status = oauthClient.authorizationInProgress()
                    ? Status.AUTHORIZING
                    : Status.DISCONNECTED;
            return;
        }

        status = Status.CONNECTING;
        startWorker(
                this::connectWithStoredToken,
                "twitchhud-eventsub-connect"
        );
    }

    public void restart(String channelName) {
        closeSocket();
        accessToken = "";
        userId = "";
        broadcasterId = "";
        userScopes = List.of();
        start(channelName);
    }

    public void stop() {
        shouldRun = false;
        reconnectScheduled.set(false);
        oauthClient.cancel();
        closeSocket();
        accessToken = "";
        userId = "";
        broadcasterId = "";
        userScopes = List.of();
        status = oauthClient.isAvailable()
                ? Status.DISCONNECTED
                : Status.DISABLED;
    }

    public CompletableFuture<
            TwitchOAuthClient.DeviceAuthorization>
            beginAuthorization() {
        if (!oauthClient.isAvailable()
                || status == Status.AUTHORIZING) {
            return CompletableFuture.completedFuture(null);
        }

        status = Status.AUTHORIZING;
        return oauthClient.beginAuthorization(
                REQUIRED_SCOPES
        ).whenComplete((authorization, error) -> {
            if (error != null) {
                status = Status.ERROR;
                return;
            }
            if (authorization == null
                    && !oauthClient.authorizationInProgress()
                    && status == Status.AUTHORIZING) {
                status = Status.DISCONNECTED;
            }
        });
    }

    private void onOAuthTokenReady() {
        if (!shouldRun || channel.isBlank()) {
            return;
        }
        closeSocket();
        status = Status.CONNECTING;
        startWorker(
                this::connectWithStoredToken,
                "twitchhud-eventsub-oauth-ready"
        );
    }

    private void connectWithStoredToken() {
        if (!shouldRun) {
            return;
        }

        TwitchOAuthClient.UserToken token =
                oauthClient.ensureUserToken("");
        if (token == null) {
            status = Status.DISCONNECTED;
            return;
        }

        accessToken = token.accessToken();
        userId = token.userId();
        userScopes = token.scopes();
        LOGGER.info(
                "TwitchHUD: Twitch user token validated; scopes="
                        + String.join(",", userScopes)
        );
        broadcasterId = resolveBroadcasterId(channel);
        if (broadcasterId.isBlank()) {
            status = Status.ERROR;
            return;
        }

        openSocket(EVENTSUB_URI, false);
    }

    private String resolveBroadcasterId(String login) {
        HttpRequest request = authorizedRequest(
                "https://api.twitch.tv/helix/users?login="
                        + encode(login)
        ).GET().build();

        try {
            HttpResponse<String> response = TwitchHttp.client().send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                return "";
            }

            JsonArray data = JsonParser.parseString(
                    response.body()
            ).getAsJsonObject().getAsJsonArray("data");
            if (data == null || data.size() == 0) {
                return "";
            }
            return string(
                    data.get(0).getAsJsonObject(),
                    "id"
            );
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: broadcaster lookup failed",
                    error
            );
            return "";
        }
    }

    private void openSocket(
            URI endpoint,
            boolean reconnect
    ) {
        WebSocket previousSocket = reconnect
                ? socket
                : null;
        int epoch = connectionEpoch.incrementAndGet();
        StringBuilder incoming = new StringBuilder();

        TwitchHttp.client().newWebSocketBuilder()
                .buildAsync(
                        endpoint,
                        new WebSocket.Listener() {
                            @Override
                            public void onOpen(
                                    WebSocket webSocket
                            ) {
                                if (epoch == connectionEpoch.get()) {
                                    socket = webSocket;
                                }
                                webSocket.request(1);
                            }

                            @Override
                            public CompletionStage<?> onText(
                                    WebSocket webSocket,
                                    CharSequence data,
                                    boolean last
                            ) {
                                incoming.append(data);
                                if (last) {
                                    String payload =
                                            incoming.toString();
                                    incoming.setLength(0);
                                    handleMessage(
                                            payload,
                                            reconnect,
                                            previousSocket
                                    );
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
                                if (epoch
                                        == connectionEpoch.get()
                                        && shouldRun) {
                                    status = Status.ERROR;
                                    scheduleReconnect();
                                }
                                return null;
                            }

                            @Override
                            public void onError(
                                    WebSocket webSocket,
                                    Throwable error
                            ) {
                                if (epoch
                                        == connectionEpoch.get()
                                        && shouldRun) {
                                    status = Status.ERROR;
                                    scheduleReconnect();
                                }
                            }
                        }
                )
                .exceptionally(error -> {
                    if (epoch == connectionEpoch.get()
                            && shouldRun) {
                        status = Status.ERROR;
                        scheduleReconnect();
                    }
                    return null;
                });
    }

    private void handleMessage(
            String payload,
            boolean reconnect,
            WebSocket previousSocket
    ) {
        JsonObject root;
        try {
            root = JsonParser.parseString(payload)
                    .getAsJsonObject();
        } catch (RuntimeException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: invalid EventSub payload",
                    error
            );
            return;
        }

        JsonObject metadata =
                root.getAsJsonObject("metadata");
        JsonObject body =
                root.getAsJsonObject("payload");
        if (body == null) {
            body = new JsonObject();
        }
        String messageType = string(
                metadata,
                "message_type"
        );

        switch (messageType) {
            case "session_welcome" -> {
                JsonObject session =
                        body.getAsJsonObject("session");
                String sessionId = string(
                        session,
                        "id"
                );
                if (reconnect) {
                    status = Status.CONNECTED;
                    if (previousSocket != null) {
                        previousSocket.sendClose(
                                WebSocket.NORMAL_CLOSURE,
                                "reconnected"
                        );
                    }
                } else {
                    createSubscriptions(sessionId);
                }
            }
            case "session_reconnect" -> {
                JsonObject session =
                        body.getAsJsonObject("session");
                String reconnectUrl = string(
                        session,
                        "reconnect_url"
                );
                if (!reconnectUrl.isBlank()) {
                    openSocket(
                            URI.create(reconnectUrl),
                            true
                    );
                }
            }
            case "notification" -> handleNotification(
                    metadata,
                    body
            );
            case "revocation" -> status = Status.ERROR;
            default -> {
            }
        }
    }

    private void createSubscriptions(String sessionId) {
        if (sessionId.isBlank()
                || broadcasterId.isBlank()
                || userId.isBlank()) {
            status = Status.ERROR;
            return;
        }

        startWorker(() -> {
            boolean anyConnected = false;
            boolean owner = channelOwnerAuthorized();
            for (SubscriptionSpec spec : SUBSCRIPTIONS) {
                if (!hasScopes(
                        userScopes,
                        spec.requiredScope()
                )) {
                    continue;
                }
                if (!spec.requiredScope().isBlank()
                        && !owner) {
                    continue;
                }
                anyConnected |= subscribe(
                        spec,
                        sessionId
                );
            }

            if (!owner) {
                LOGGER.warning(
                        "TwitchHUD: protected channel events require "
                                + "authorization from the configured "
                                + "broadcaster account"
                );
            }

            status = anyConnected
                    ? Status.CONNECTED
                    : Status.ERROR;
        }, "twitchhud-eventsub-subscribe");
    }

    private boolean subscribe(
            SubscriptionSpec spec,
            String sessionId
    ) {
        JsonObject condition = new JsonObject();
        condition.addProperty(
                "broadcaster_user_id",
                broadcasterId
        );
        JsonObject transport = new JsonObject();
        transport.addProperty("method", "websocket");
        transport.addProperty("session_id", sessionId);

        JsonObject body = new JsonObject();
        body.addProperty("type", spec.type());
        body.addProperty("version", spec.version());
        body.add("condition", condition);
        body.add("transport", transport);

        HttpRequest request = authorizedRequest(
                "https://api.twitch.tv/helix"
                        + "/eventsub/subscriptions"
        )
                .header(
                        "Content-Type",
                        "application/json"
                )
                .POST(HttpRequest.BodyPublishers.ofString(
                        body.toString(),
                        StandardCharsets.UTF_8
                ))
                .build();

        try {
            HttpResponse<String> response = TwitchHttp.client().send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 202) {
                LOGGER.info(
                        "TwitchHUD: EventSub subscribed "
                                + spec.type()
                );
                return true;
            }
            LOGGER.warning(
                    "TwitchHUD: EventSub "
                            + spec.type()
                            + " failed with "
                            + response.statusCode()
                            + ": "
                            + response.body()
            );
            return false;
        } catch (IOException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: EventSub subscription failed",
                    error
            );
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void handleNotification(
            JsonObject metadata,
            JsonObject body
    ) {
        JsonObject subscription =
                body.getAsJsonObject("subscription");
        JsonObject event =
                body.getAsJsonObject("event");
        if (subscription == null || event == null) {
            return;
        }

        String type = string(subscription, "type");
        switch (type) {
            case "channel.channel_points_custom_reward_redemption.add" ->
                    handleReward(metadata, event);
            case "channel.goal.begin",
                    "channel.goal.progress" ->
                    liveState.upsertGoal(parseGoal(event));
            case "channel.goal.end" ->
                    liveState.removeGoal(string(event, "id"));
            case "channel.hype_train.begin",
                    "channel.hype_train.progress" ->
                    liveState.hypeTrain(parseHype(event));
            case "channel.hype_train.end" ->
                    liveState.hypeTrain(
                            new TwitchLiveState.HypeTrain(
                                    false,
                                    0,
                                    0,
                                    0,
                                    0
                            )
                    );
            case "channel.update" ->
                    liveState.title(string(event, "title"));
            case "stream.online" ->
                    liveState.stream(
                            true,
                            liveState.snapshot().viewerCount()
                    );
            case "stream.offline" ->
                    liveState.stream(false, 0);
            default -> {
            }
        }
    }

    private void handleReward(
            JsonObject metadata,
            JsonObject event
    ) {
        String login = string(event, "user_login");
        String displayName = string(event, "user_name");
        if (displayName.isBlank()) {
            displayName = login;
        }
        if (login.isBlank()) {
            login = displayName.toLowerCase(Locale.ROOT);
        }

        JsonObject reward = event.has("reward")
                && event.get("reward").isJsonObject()
                ? event.getAsJsonObject("reward")
                : new JsonObject();
        String title = string(reward, "title");
        long cost = number(reward, "cost");
        String input = string(event, "user_input");
        String rewardLabel = title.isBlank()
                ? "a channel points reward"
                : title;
        String systemText =
                displayName + " redeemed " + rewardLabel;
        if (cost > 0) {
            systemText += " (" + cost + " points)";
        }
        if (!input.isBlank()) {
            systemText += ": " + input;
        }

        messageConsumer.accept(new ChatMessage(
                messageId(metadata, "reward"),
                login,
                displayName,
                input,
                TwitchUserColor.resolve(null, login),
                false,
                false,
                false,
                List.of(),
                List.of(),
                System.currentTimeMillis(),
                ChatMessage.Kind.REDEMPTION,
                systemText
        ));
    }

    private static TwitchLiveState.CreatorGoal parseGoal(
            JsonObject event
    ) {
        return new TwitchLiveState.CreatorGoal(
                string(event, "id"),
                string(event, "type"),
                string(event, "description"),
                number(event, "current_amount"),
                number(event, "target_amount")
        );
    }

    private static TwitchLiveState.HypeTrain parseHype(
            JsonObject event
    ) {
        return new TwitchLiveState.HypeTrain(
                true,
                integer(event, "level"),
                number(event, "progress"),
                number(event, "goal"),
                parseTimestamp(string(event, "expires_at"))
        );
    }

    private HttpRequest.Builder authorizedRequest(
            String url
    ) {
        return TwitchHttp.request(url)
                .header(
                        "Client-Id",
                        oauthClient.clientId()
                )
                .header(
                        "Authorization",
                        "Bearer " + accessToken
                );
    }

    private void scheduleReconnect() {
        if (!shouldRun
                || !reconnectScheduled.compareAndSet(
                        false,
                        true
                )) {
            return;
        }

        int scheduledEpoch = connectionEpoch.get();
        startWorker(() -> {
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                reconnectScheduled.set(false);
            }

            if (shouldRun
                    && scheduledEpoch
                    == connectionEpoch.get()) {
                status = Status.CONNECTING;
                connectWithStoredToken();
            }
        }, "twitchhud-eventsub-reconnect");
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

    private static boolean hasScopes(
            List<String> granted,
            String required
    ) {
        if (required == null || required.isBlank()) {
            return true;
        }
        for (String scope : required.trim().split("\\s+")) {
            if (!granted.contains(scope)) {
                return false;
            }
        }
        return true;
    }

    private static String messageId(
            JsonObject metadata,
            String prefix
    ) {
        String id = string(metadata, "message_id");
        return id.isBlank()
                ? prefix + "-" + System.nanoTime()
                : id;
    }

    private static String normalizeChannel(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace("#", "");
    }

    private static String string(
            JsonObject object,
            String key
    ) {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static int integer(
            JsonObject object,
            String key
    ) {
        return (int) number(object, key);
    }

    private static long number(
            JsonObject object,
            String key
    ) {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException error) {
            return 0;
        }
    }

    private static long parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException error) {
            return System.currentTimeMillis();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(
                value == null ? "" : value,
                StandardCharsets.UTF_8
        );
    }

    private static void startWorker(
            Runnable task,
            String name
    ) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }
}
