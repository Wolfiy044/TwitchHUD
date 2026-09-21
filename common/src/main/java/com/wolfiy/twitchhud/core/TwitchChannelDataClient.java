package com.wolfiy.twitchhud.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TwitchChannelDataClient {
    private static final Logger LOGGER =
            Logger.getLogger("TwitchHUD");
    private static final long LOOP_SLEEP_MS = 200L;
    private static final long LIVE_REFRESH_MS = 2_000L;
    private static final long FOLLOWER_REFRESH_MS = 2_000L;
    private static final long PROTECTED_REFRESH_MS = 5_000L;
    private static final long PUBLIC_FALLBACK_MS = 5_000L;
    private static final long PUBLIC_FOLLOWER_FALLBACK_MS = 2_000L;
    private static final String DECAPI_BASE =
            "https://decapi.me/twitch";

    private final TwitchEventSubClient eventSubClient;
    private final TwitchHelixClient helixClient;
    private final TwitchHudConfig config;
    private final TwitchLiveState liveState;
    private final Consumer<ChatMessage> messageConsumer;
    private final AtomicBoolean running =
            new AtomicBoolean();
    private final AtomicBoolean forceRefresh =
            new AtomicBoolean();
    private final AtomicBoolean liveInFlight =
            new AtomicBoolean();
    private final AtomicBoolean followerInFlight =
            new AtomicBoolean();
    private final AtomicBoolean protectedInFlight =
            new AtomicBoolean();
    private final Map<String, Integer> lastHttpFailures =
            new ConcurrentHashMap<>();
    private final ExecutorService requests =
            Executors.newFixedThreadPool(
                    3,
                    daemonThreadFactory()
            );

    private volatile String activeChannel = "";
    private volatile String publicBroadcasterId = "";
    private volatile int observedFollowerCount = -1;
    private volatile int followerNotificationHighWater = -1;
    private volatile long rateLimitUntilMillis;
    private volatile long lastPublicLiveAt;
    private volatile long lastPublicFollowerAt;
    private Thread scheduler;

    public TwitchChannelDataClient(
            TwitchEventSubClient eventSubClient,
            TwitchHelixClient helixClient,
            TwitchHudConfig config,
            TwitchLiveState liveState,
            Consumer<ChatMessage> messageConsumer
    ) {
        this.eventSubClient = eventSubClient;
        this.helixClient = helixClient;
        this.config = config;
        this.liveState = liveState;
        this.messageConsumer = messageConsumer;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler = new Thread(
                this::runLoop,
                "twitchhud-channel-scheduler"
        );
        scheduler.setDaemon(true);
        scheduler.start();
    }

    public void stop() {
        running.set(false);
        Thread activeScheduler = scheduler;
        if (activeScheduler != null) {
            activeScheduler.interrupt();
        }
        requests.shutdownNow();
    }

    public void refreshSoon() {
        forceRefresh.set(true);
        Thread activeScheduler = scheduler;
        if (activeScheduler != null) {
            activeScheduler.interrupt();
        }
    }

    private void runLoop() {
        long nextLiveRefresh = 0L;
        long nextFollowerRefresh = 0L;
        long nextProtectedRefresh = 0L;

        while (running.get()) {
            String channel = normalizedChannel();
            if (!channel.equals(activeChannel)) {
                activeChannel = channel;
                publicBroadcasterId = "";
                observedFollowerCount = -1;
                followerNotificationHighWater = -1;
                rateLimitUntilMillis = 0L;
                lastPublicLiveAt = 0L;
                lastPublicFollowerAt = 0L;
                nextLiveRefresh = 0L;
                nextFollowerRefresh = 0L;
                nextProtectedRefresh = 0L;
            }

            if (forceRefresh.getAndSet(false)) {
                nextLiveRefresh = 0L;
                nextFollowerRefresh = 0L;
                nextProtectedRefresh = 0L;
                lastPublicLiveAt = 0L;
                lastPublicFollowerAt = 0L;
            }

            if (!channel.isBlank()) {
                long now = System.currentTimeMillis();
                if (now >= nextLiveRefresh) {
                    submit(
                            liveInFlight,
                            () -> refreshLive(channel)
                    );
                    nextLiveRefresh = now + LIVE_REFRESH_MS;
                }
                if (now >= nextFollowerRefresh) {
                    submit(
                            followerInFlight,
                            () -> refreshFollower(channel)
                    );
                    nextFollowerRefresh =
                            now + FOLLOWER_REFRESH_MS;
                }
                if (now >= nextProtectedRefresh) {
                    submit(
                            protectedInFlight,
                            () -> refreshProtected(channel)
                    );
                    nextProtectedRefresh =
                            now + PROTECTED_REFRESH_MS;
                }
            }

            try {
                Thread.sleep(LOOP_SLEEP_MS);
            } catch (InterruptedException error) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void submit(
            AtomicBoolean inFlight,
            Runnable task
    ) {
        if (!running.get()
                || !inFlight.compareAndSet(false, true)) {
            return;
        }
        requests.execute(() -> {
            try {
                task.run();
            } finally {
                inFlight.set(false);
            }
        });
    }

    private void refreshLive(String channel) {
        TwitchEventSubClient.AuthContext auth =
                preferredAuth(channel);
        boolean streamUpdated = false;
        boolean titleUpdated = false;

        if (canUseOfficial(auth)) {
            streamUpdated = refreshStream(
                    channel,
                    auth
            );
            titleUpdated = refreshChannel(
                    channel,
                    auth
            );
        }

        if ((!streamUpdated || !titleUpdated)
                && canUsePublicFallback(
                        lastPublicLiveAt
                )) {
            lastPublicLiveAt =
                    System.currentTimeMillis();
            refreshPublicLive(
                    channel,
                    !titleUpdated,
                    !streamUpdated
            );
        }
    }

    private void refreshFollower(String channel) {
        long now = System.currentTimeMillis();
        if (now - lastPublicFollowerAt
                < PUBLIC_FOLLOWER_FALLBACK_MS) {
            return;
        }

        lastPublicFollowerAt = now;
        refreshPublicFollowerCount(channel);
    }

    private void refreshProtected(String channel) {
        TwitchEventSubClient.AuthContext auth =
                eventSubClient.authContext();
        if (!canUseOfficial(auth)
                || !auth.ownsBroadcaster()
                || !isCurrentChannel(channel)) {
            return;
        }

        if (auth.hasScope("channel:read:goals")) {
            refreshGoals(channel, auth);
        }
        if (auth.hasScope("channel:read:hype_train")) {
            refreshHypeTrain(channel, auth);
        }
    }

    private TwitchEventSubClient.AuthContext preferredAuth(
            String channel
    ) {
        TwitchEventSubClient.AuthContext userAuth =
                eventSubClient.authContext();
        if (userAuth.ready()) {
            return userAuth;
        }
        return publicAppAuth(channel);
    }

    private boolean canUseOfficial(
            TwitchEventSubClient.AuthContext auth
    ) {
        return auth != null
                && auth.ready()
                && System.currentTimeMillis()
                        >= rateLimitUntilMillis;
    }

    private boolean canUsePublicFallback(long lastRun) {
        return System.currentTimeMillis() - lastRun
                >= PUBLIC_FALLBACK_MS;
    }

    private synchronized TwitchEventSubClient.AuthContext
            publicAppAuth(String channel) {
        if (!isCurrentChannel(channel)) {
            return null;
        }

        TwitchHelixClient.AppAuth appAuth =
                helixClient.appAuth();
        if (appAuth == null || !appAuth.ready()) {
            return null;
        }

        if (publicBroadcasterId.isBlank()) {
            TwitchEventSubClient.AuthContext lookupAuth =
                    new TwitchEventSubClient.AuthContext(
                            appAuth.clientId(),
                            appAuth.accessToken(),
                            "",
                            "",
                            List.of()
                    );
            JsonObject root = get(
                    lookupAuth,
                    "/helix/users?login=" + encode(channel)
            );
            JsonArray data = array(root);
            if (data.isEmpty()
                    || !isCurrentChannel(channel)) {
                return null;
            }
            publicBroadcasterId = string(
                    data.get(0).getAsJsonObject(),
                    "id"
            );
        }

        return new TwitchEventSubClient.AuthContext(
                appAuth.clientId(),
                appAuth.accessToken(),
                "",
                publicBroadcasterId,
                List.of()
        );
    }

    private void refreshPublicLive(
            String channel,
            boolean refreshTitle,
            boolean refreshViewers
    ) {
        if (refreshTitle) {
            String title = publicText(
                    "title",
                    DECAPI_BASE + "/title/"
                            + encode(channel)
            );
            if (usableText(title)
                    && isCurrentChannel(channel)) {
                liveState.title(title.trim());
            }
        }

        if (!refreshViewers) {
            return;
        }

        String viewers = publicText(
                "viewercount",
                DECAPI_BASE + "/viewercount/"
                        + encode(channel)
        );
        if (!usableText(viewers)
                || !isCurrentChannel(channel)) {
            return;
        }

        String normalized = viewers.trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.contains("offline")) {
            liveState.stream(false, 0);
            return;
        }

        Integer count = parseInteger(viewers);
        if (count != null) {
            liveState.stream(true, count);
        }
    }

    private void refreshPublicFollowerCount(String channel) {
        Integer count = parseInteger(publicText(
                "followcount",
                DECAPI_BASE + "/followcount/" + encode(channel)
        ));
        if (count != null && isCurrentChannel(channel)) {
            applyFollowerCount(count);
        }
    }

    private void applyFollowerCount(int count) {
        int safeCount = Math.max(0, count);
        observedFollowerCount = safeCount;
        liveState.followerCount(safeCount);

        int previousHighWater = followerNotificationHighWater;
        if (previousHighWater < 0) {
            followerNotificationHighWater = safeCount;
            return;
        }
        if (safeCount <= previousHighWater) {
            return;
        }

        followerNotificationHighWater = safeCount;
        int delta = safeCount - previousHighWater;
        String message = delta == 1
                ? "New follower · " + safeCount + " total"
                : delta + " new followers · "
                        + safeCount + " total";
        messageConsumer.accept(new ChatMessage(
                "follow-count-" + System.nanoTime(),
                "",
                "",
                "",
                0xFFFFFF,
                false,
                false,
                false,
                List.of(),
                List.of(),
                System.currentTimeMillis(),
                ChatMessage.Kind.FOLLOW,
                message
        ));
    }

    private boolean refreshStream(
            String channel,
            TwitchEventSubClient.AuthContext auth
    ) {
        JsonObject root = get(
                auth,
                "/helix/streams?user_id="
                        + encode(auth.broadcasterId())
        );
        if (!root.has("data")
                || !isCurrentChannel(channel)) {
            return false;
        }

        JsonArray data = array(root);
        if (data.isEmpty()) {
            liveState.stream(false, 0);
            return true;
        }

        JsonObject stream = data.get(0).getAsJsonObject();
        liveState.stream(
                true,
                integer(stream, "viewer_count")
        );
        String title = string(stream, "title");
        if (!title.isBlank()) {
            liveState.title(title);
        }
        return true;
    }

    private boolean refreshChannel(
            String channel,
            TwitchEventSubClient.AuthContext auth
    ) {
        JsonObject root = get(
                auth,
                "/helix/channels?broadcaster_id="
                        + encode(auth.broadcasterId())
        );
        if (!root.has("data")
                || !isCurrentChannel(channel)) {
            return false;
        }

        JsonArray data = array(root);
        if (data.isEmpty()) {
            return false;
        }
        liveState.title(
                string(data.get(0).getAsJsonObject(), "title")
        );
        return true;
    }

    private void refreshGoals(
            String channel,
            TwitchEventSubClient.AuthContext auth
    ) {
        JsonObject root = get(
                auth,
                "/helix/goals?broadcaster_id="
                        + encode(auth.broadcasterId())
        );
        if (!root.has("data")
                || !isCurrentChannel(channel)) {
            return;
        }

        JsonArray data = array(root);
        List<TwitchLiveState.CreatorGoal> goals =
                new ArrayList<>();
        for (JsonElement element : data) {
            JsonObject goal = element.getAsJsonObject();
            String id = string(goal, "id");
            if (id.isBlank()) {
                continue;
            }
            goals.add(new TwitchLiveState.CreatorGoal(
                    id,
                    string(goal, "type"),
                    string(goal, "description"),
                    number(goal, "current_amount"),
                    number(goal, "target_amount")
            ));
        }
        if (isCurrentChannel(channel)) {
            liveState.replaceGoals(goals);
        }
    }

    private void refreshHypeTrain(
            String channel,
            TwitchEventSubClient.AuthContext auth
    ) {
        JsonObject root = get(
                auth,
                "/helix/hypetrain/status?broadcaster_id="
                        + encode(auth.broadcasterId())
        );
        if (!root.has("data")
                || !isCurrentChannel(channel)) {
            return;
        }

        JsonArray data = array(root);
        if (data.isEmpty()) {
            liveState.hypeTrain(
                    new TwitchLiveState.HypeTrain(
                            false,
                            0,
                            0,
                            0,
                            0
                    )
            );
            return;
        }

        JsonObject current = object(
                data.get(0).getAsJsonObject(),
                "current"
        );
        if (current.isEmpty()) {
            liveState.hypeTrain(
                    new TwitchLiveState.HypeTrain(
                            false,
                            0,
                            0,
                            0,
                            0
                    )
            );
            return;
        }

        liveState.hypeTrain(
                new TwitchLiveState.HypeTrain(
                        true,
                        integer(current, "level"),
                        number(current, "progress"),
                        number(current, "goal"),
                        timestamp(current, "expires_at")
                )
        );
    }

    private JsonObject get(
            TwitchEventSubClient.AuthContext auth,
            String path
    ) {
        HttpRequest request = TwitchHttp.request(
                        "https://api.twitch.tv" + path
                )
                .header("Client-Id", auth.clientId())
                .header(
                        "Authorization",
                        "Bearer " + auth.accessToken()
                )
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    TwitchHttp.client().send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );
            updateRateLimit(response);
            if (response.statusCode() != 200) {
                logHttpFailure(
                        "helix:" + endpointKey(path),
                        response.statusCode(),
                        response.body()
                );
                return new JsonObject();
            }

            lastHttpFailures.remove(
                    "helix:" + endpointKey(path)
            );
            return JsonParser.parseString(
                    response.body()
            ).getAsJsonObject();
        } catch (IOException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: Helix request failed for "
                            + endpointKey(path),
                    error
            );
            return new JsonObject();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new JsonObject();
        } catch (RuntimeException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: Helix parse failed for "
                            + endpointKey(path),
                    error
            );
            return new JsonObject();
        }
    }

    private String publicText(
            String key,
            String url
    ) {
        try {
            HttpResponse<String> response =
                    TwitchHttp.client().send(
                            TwitchHttp.request(url)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
            if (response.statusCode() != 200) {
                logHttpFailure(
                        "public:" + key,
                        response.statusCode(),
                        response.body()
                );
                return "";
            }

            lastHttpFailures.remove("public:" + key);
            return response.body() == null
                    ? ""
                    : response.body().trim();
        } catch (IOException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: public fallback failed for " + key,
                    error
            );
            return "";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private void updateRateLimit(
            HttpResponse<?> response
    ) {
        if (response.statusCode() != 429) {
            return;
        }

        long fallback = System.currentTimeMillis() + 60_000L;
        rateLimitUntilMillis = response.headers()
                .firstValue("Ratelimit-Reset")
                .map(value -> {
                    try {
                        return Long.parseLong(value) * 1000L;
                    } catch (NumberFormatException error) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private void logHttpFailure(
            String key,
            int status,
            String body
    ) {
        Integer previous = lastHttpFailures.put(
                key,
                status
        );
        if (previous != null && previous == status) {
            return;
        }

        String detail = body == null
                ? ""
                : body.replaceAll("\\s+", " ").trim();
        if (detail.length() > 180) {
            detail = detail.substring(0, 180) + "...";
        }
        LOGGER.warning(
                "TwitchHUD: "
                        + key
                        + " returned HTTP "
                        + status
                        + (detail.isBlank()
                                ? ""
                                : " - " + detail)
        );
    }

    private boolean isCurrentChannel(String channel) {
        return running.get()
                && channel.equals(normalizedChannel());
    }

    private String normalizedChannel() {
        String channel = config.channel();
        return channel == null
                ? ""
                : channel.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace("#", "");
    }

    private static Integer parseInteger(String value) {
        if (!usableText(value)) {
            return null;
        }

        String cleaned = value
                .replace(",", "")
                .replace(".", "")
                .replaceAll("[^0-9-]", "")
                .trim();
        if (cleaned.isBlank()) {
            return null;
        }

        try {
            return Math.max(0, Integer.parseInt(cleaned));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static boolean usableText(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.trim()
                .toLowerCase(Locale.ROOT);
        return !lower.contains("error")
                && !lower.contains("not found")
                && !lower.startsWith("decapi:");
    }

    private static String endpointKey(String path) {
        int query = path.indexOf('?');
        return query >= 0
                ? path.substring(0, query)
                : path;
    }

    private static JsonArray array(JsonObject root) {
        if (root == null || !root.has("data")) {
            return new JsonArray();
        }
        JsonElement data = root.get("data");
        return data != null && data.isJsonArray()
                ? data.getAsJsonArray()
                : new JsonArray();
    }

    private static JsonObject object(
            JsonObject root,
            String key
    ) {
        if (root == null
                || !root.has(key)
                || !root.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return root.getAsJsonObject(key);
    }

    private static String string(
            JsonObject root,
            String key
    ) {
        if (root == null
                || !root.has(key)
                || root.get(key).isJsonNull()) {
            return "";
        }
        return root.get(key).getAsString();
    }

    private static int integer(
            JsonObject root,
            String key
    ) {
        return (int) number(root, key);
    }

    private static long number(
            JsonObject root,
            String key
    ) {
        if (root == null
                || !root.has(key)
                || root.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return root.get(key).getAsLong();
        } catch (RuntimeException error) {
            return 0;
        }
    }

    private static long timestamp(
            JsonObject root,
            String key
    ) {
        String value = string(root, key);
        if (value.isBlank()) {
            return 0;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException error) {
            return 0;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(
                value == null ? "" : value,
                StandardCharsets.UTF_8
        );
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(
                    task,
                    "twitchhud-channel-data-"
                            + counter.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
    }
}
