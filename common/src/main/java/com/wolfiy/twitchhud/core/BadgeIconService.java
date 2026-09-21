package com.wolfiy.twitchhud.core;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class BadgeIconService {
    private static final Logger LOGGER =
            Logger.getLogger("TwitchHUD");
    private static final long GLOBAL_REFRESH_MS =
            30 * 60 * 1000L;
    private static final long CHANNEL_REFRESH_MS =
            10 * 60 * 1000L;
    private static final long RETRY_MS = 30_000L;

    private final TwitchHelixClient helixClient;
    private final HttpImageCache imageCache =
            new HttpImageCache();
    private final Map<String, Map<String, String>>
            globalBadges = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>>
            channelBadges = new ConcurrentHashMap<>();
    private final AtomicReference<String> loadedChannelId =
            new AtomicReference<>("");
    private final AtomicReference<String> loadingChannelId =
            new AtomicReference<>("");
    private final AtomicBoolean globalLoading =
            new AtomicBoolean();
    private final AtomicInteger metadataVersion =
            new AtomicInteger();

    private volatile long globalLoadedAt;
    private volatile long globalAttemptAt;
    private volatile long channelLoadedAt;
    private volatile long channelAttemptAt;

    public BadgeIconService(TwitchHelixClient helixClient) {
        this.helixClient = helixClient;
    }

    public boolean isEnabled() {
        return true;
    }

    public void ensureGlobalLoaded() {
        long now = System.currentTimeMillis();
        if (!globalBadges.isEmpty()
                && now - globalLoadedAt < GLOBAL_REFRESH_MS) {
            return;
        }
        if (now - globalAttemptAt < RETRY_MS
                || !globalLoading.compareAndSet(false, true)) {
            return;
        }

        globalAttemptAt = now;
        LOGGER.info("TwitchHUD: fetching global badges...");
        helixClient.fetchGlobalBadges()
                .thenAccept(result -> {
                    if (result.isEmpty()) {
                        return;
                    }
                    globalBadges.clear();
                    globalBadges.putAll(result);
                    globalLoadedAt =
                            System.currentTimeMillis();
                    metadataVersion.incrementAndGet();
                    LOGGER.info(
                            "TwitchHUD: global badges loaded, "
                                    + result.size()
                                    + " sets"
                    );
                })
                .whenComplete(
                        (ignored, error) ->
                                globalLoading.set(false)
                );
    }

    public void ensureChannelLoaded(String broadcasterId) {
        if (broadcasterId == null
                || broadcasterId.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (broadcasterId.equals(loadedChannelId.get())
                && now - channelLoadedAt
                        < CHANNEL_REFRESH_MS) {
            return;
        }
        if (broadcasterId.equals(loadedChannelId.get())
                && now - channelAttemptAt < RETRY_MS) {
            return;
        }
        if (broadcasterId.equals(
                loadingChannelId.get()
        )) {
            return;
        }

        loadedChannelId.set(broadcasterId);
        loadingChannelId.set(broadcasterId);
        channelAttemptAt = now;
        channelBadges.clear();
        LOGGER.info(
                "TwitchHUD: fetching channel badges for broadcaster "
                        + broadcasterId
                        + "..."
        );

        helixClient.fetchChannelBadges(broadcasterId)
                .thenAccept(result -> {
                    if (!broadcasterId.equals(
                            loadedChannelId.get()
                    )) {
                        return;
                    }
                    channelBadges.clear();
                    channelBadges.putAll(result);
                    channelLoadedAt =
                            System.currentTimeMillis();
                    metadataVersion.incrementAndGet();
                    LOGGER.info(
                            "TwitchHUD: channel badges loaded, "
                                    + result.size()
                                    + " sets"
                    );
                })
                .whenComplete((ignored, error) ->
                        loadingChannelId.compareAndSet(
                                broadcasterId,
                                ""
                        )
                );
    }

    public Optional<String> iconUrl(
            String setId,
            String version
    ) {
        Map<String, String> channelVersions =
                channelBadges.get(setId);
        if (channelVersions != null) {
            String url = channelVersions.get(version);
            if (url != null && !url.isBlank()) {
                return Optional.of(url);
            }
        }

        Map<String, String> globalVersions =
                globalBadges.get(setId);
        if (globalVersions != null) {
            String url = globalVersions.get(version);
            if (url != null && !url.isBlank()) {
                return Optional.of(url);
            }
        }
        return Optional.empty();
    }

    public int metadataVersion() {
        return metadataVersion.get();
    }

    public HttpImageCache imageCache() {
        return imageCache;
    }
}
