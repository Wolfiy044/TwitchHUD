package com.wolfiy.twitchhud.core;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class HttpImageCache {
    private static final int MAX_ENTRIES = 256;

    private final Map<String, CompletableFuture<byte[]>> cache =
            new LinkedHashMap<>(64, 0.75f, true) {
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

    public CompletableFuture<byte[]> fetch(String url) {
        CompletableFuture<byte[]> existing;
        synchronized (cache) {
            existing = cache.get(url);
            if (existing != null) {
                return existing;
            }
        }

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
            existing = cache.get(url);
            if (existing != null) {
                return existing;
            }
            cache.put(url, created);
        }

        created.whenComplete((bytes, error) -> {
            if (error == null
                    && bytes != null
                    && bytes.length > 0) {
                return;
            }
            synchronized (cache) {
                cache.remove(url, created);
            }
        });
        return created;
    }
}
