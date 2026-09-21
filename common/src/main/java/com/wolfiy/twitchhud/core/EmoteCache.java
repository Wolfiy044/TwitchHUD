package com.wolfiy.twitchhud.core;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class EmoteCache {
    private static final int MAX_ENTRIES = 512;

    private final Map<String, CompletableFuture<byte[]>> cache =
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<
                                String,
                                CompletableFuture<byte[]>
                        > eldest
                ) {
                    return size() > MAX_ENTRIES;
                }
            };

    public CompletableFuture<byte[]> fetch(String emoteId, String imageUrl) {
        CompletableFuture<byte[]> existing;
        synchronized (cache) {
            existing = cache.get(emoteId);
            if (existing != null) {
                return existing;
            }
        }

        String url = imageUrl != null
                ? imageUrl
                : "https://static-cdn.jtvnw.net/emoticons/v2/"
                        + emoteId
                        + "/static/dark/2.0";
        CompletableFuture<byte[]> created =
                TwitchHttp.client()
                        .sendAsync(
                                TwitchHttp.request(url)
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofByteArray()
                        )
                        .thenApply(response ->
                                response.statusCode() == 200
                                        ? response.body()
                                        : new byte[0]
                        )
                        .exceptionally(error -> new byte[0]);

        synchronized (cache) {
            existing = cache.get(emoteId);
            if (existing != null) {
                return existing;
            }
            cache.put(emoteId, created);
        }

        created.whenComplete((bytes, error) -> {
            if (error == null
                    && bytes != null
                    && bytes.length > 0) {
                return;
            }
            synchronized (cache) {
                cache.remove(emoteId, created);
            }
        });
        return created;
    }

    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }
}
