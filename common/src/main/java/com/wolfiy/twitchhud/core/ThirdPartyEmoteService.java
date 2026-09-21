package com.wolfiy.twitchhud.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ThirdPartyEmoteService {
    public record EmoteInfo(String provider, String id, String url) {
    }

    private static final Logger LOGGER = Logger.getLogger("TwitchHUD");
    private static final long GLOBAL_REFRESH_MS = 30 * 60 * 1000L;
    private static final long CHANNEL_REFRESH_MS = 10 * 60 * 1000L;
    private static final long RETRY_MS = 30_000L;

    private final Map<String, EmoteInfo> globalEmotes = new ConcurrentHashMap<>();
    private final Map<String, EmoteInfo> channelEmotes = new ConcurrentHashMap<>();
    private final AtomicReference<String> loadedChannelId = new AtomicReference<>("");
    private final AtomicReference<String> loadingChannelId = new AtomicReference<>("");
    private final AtomicBoolean globalLoading = new AtomicBoolean();
    private final AtomicInteger metadataVersion = new AtomicInteger();

    private volatile long globalLoadedAt;
    private volatile long globalAttemptAt;
    private volatile long channelLoadedAt;
    private volatile long channelAttemptAt;

    public void ensureGlobalLoaded() {
        long now = System.currentTimeMillis();
        if (!globalEmotes.isEmpty() && now - globalLoadedAt < GLOBAL_REFRESH_MS) {
            return;
        }
        if (now - globalAttemptAt < RETRY_MS || !globalLoading.compareAndSet(false, true)) {
            return;
        }
        globalAttemptAt = now;

        CompletableFuture<Map<String, EmoteInfo>> sevenTv = fetchSevenTvGlobal();
        CompletableFuture<Map<String, EmoteInfo>> bttv = fetchBttvGlobal();
        CompletableFuture<Map<String, EmoteInfo>> ffz = fetchFfzGlobal();

        CompletableFuture.allOf(sevenTv, bttv, ffz)
                .thenRun(() -> {
                    Map<String, EmoteInfo> combined = new LinkedHashMap<>();
                    combined.putAll(ffz.join());
                    combined.putAll(bttv.join());
                    combined.putAll(sevenTv.join());
                    if (combined.isEmpty()) {
                        return;
                    }
                    globalEmotes.clear();
                    globalEmotes.putAll(combined);
                    globalLoadedAt = System.currentTimeMillis();
                    metadataVersion.incrementAndGet();
                    LOGGER.info("TwitchHUD: third-party global emotes loaded, " + combined.size());
                })
                .whenComplete((ignored, error) -> globalLoading.set(false));
    }

    public void ensureChannelLoaded(String broadcasterId, String channelLogin) {
        if (broadcasterId == null || broadcasterId.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (broadcasterId.equals(loadedChannelId.get()) && now - channelLoadedAt < CHANNEL_REFRESH_MS) {
            return;
        }
        if (broadcasterId.equals(loadedChannelId.get()) && now - channelAttemptAt < RETRY_MS) {
            return;
        }
        if (broadcasterId.equals(loadingChannelId.get())) {
            return;
        }

        loadedChannelId.set(broadcasterId);
        loadingChannelId.set(broadcasterId);
        channelAttemptAt = now;
        channelEmotes.clear();

        CompletableFuture<Map<String, EmoteInfo>> sevenTv = fetchSevenTvChannel(broadcasterId);
        CompletableFuture<Map<String, EmoteInfo>> bttv = fetchBttvChannel(broadcasterId);
        CompletableFuture<Map<String, EmoteInfo>> ffz = fetchFfzChannel(channelLogin);

        CompletableFuture.allOf(sevenTv, bttv, ffz)
                .thenRun(() -> {
                    if (!broadcasterId.equals(loadedChannelId.get())) {
                        return;
                    }
                    Map<String, EmoteInfo> combined = new LinkedHashMap<>();
                    combined.putAll(ffz.join());
                    combined.putAll(bttv.join());
                    combined.putAll(sevenTv.join());
                    channelEmotes.clear();
                    channelEmotes.putAll(combined);
                    channelLoadedAt = System.currentTimeMillis();
                    metadataVersion.incrementAndGet();
                    LOGGER.info("TwitchHUD: third-party channel emotes loaded, " + combined.size());
                })
                .whenComplete((ignored, error) -> loadingChannelId.compareAndSet(broadcasterId, ""));
    }

    public void clearChannel() {
        channelEmotes.clear();
        loadedChannelId.set("");
    }

    public Optional<EmoteInfo> find(String word) {
        EmoteInfo channel = channelEmotes.get(word);
        if (channel != null) {
            return Optional.of(channel);
        }
        return Optional.ofNullable(globalEmotes.get(word));
    }

    public int metadataVersion() {
        return metadataVersion.get();
    }

    private CompletableFuture<Map<String, EmoteInfo>> fetchSevenTvGlobal() {
        return TwitchHttp.client()
                .sendAsync(TwitchHttp.request("https://7tv.io/v3/emote-sets/global").GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() == 200
                        ? parseSevenTvEmotes(safeObject(response.body()).getAsJsonArray("emotes"))
                        : Map.<String, EmoteInfo>of())
                .exceptionally(error -> {
                    LOGGER.log(Level.WARNING, "TwitchHUD: 7TV global emote fetch failed", error);
                    return Map.of();
                });
    }

    private CompletableFuture<Map<String, EmoteInfo>> fetchSevenTvChannel(String broadcasterId) {
        return TwitchHttp.client()
                .sendAsync(TwitchHttp.request("https://7tv.io/v3/users/twitch/" + encode(broadcasterId)).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return Map.<String, EmoteInfo>of();
                    }
                    JsonObject emoteSet = safeObject(response.body()).getAsJsonObject("emote_set");
                    return emoteSet == null
                            ? Map.<String, EmoteInfo>of()
                            : parseSevenTvEmotes(emoteSet.getAsJsonArray("emotes"));
                })
                .exceptionally(error -> {
                    LOGGER.log(Level.WARNING, "TwitchHUD: 7TV channel emote fetch failed", error);
                    return Map.of();
                });
    }

    private static Map<String, EmoteInfo> parseSevenTvEmotes(JsonArray emotes) {
        Map<String, EmoteInfo> result = new LinkedHashMap<>();
        if (emotes == null) {
            return result;
        }
        for (JsonElement element : emotes) {
            JsonObject emote = element.getAsJsonObject();
            if (!emote.has("name") || !emote.has("id") || !emote.has("data")) {
                continue;
            }
            JsonObject host = emote.getAsJsonObject("data").getAsJsonObject("host");
            if (host == null || !host.has("url") || !host.has("files")) {
                continue;
            }
            String fileName = pickSevenTvFile(host.getAsJsonArray("files"));
            if (fileName == null) {
                continue;
            }
            String name = emote.get("name").getAsString();
            String id = emote.get("id").getAsString();
            String url = "https:" + host.get("url").getAsString() + "/" + fileName;
            result.put(name, new EmoteInfo("7TV", id, url));
        }
        return result;
    }

    private static String pickSevenTvFile(JsonArray files) {
        String fallback = null;
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if (!"GIF".equals(string(file, "format")) || !file.has("static_name")) {
                continue;
            }
            String staticName = file.get("static_name").getAsString();
            if ("2x.gif".equals(string(file, "name"))) {
                return staticName;
            }
            if (fallback == null) {
                fallback = staticName;
            }
        }
        return fallback;
    }

    private CompletableFuture<Map<String, EmoteInfo>> fetchBttvGlobal() {
        return TwitchHttp.client()
                .sendAsync(TwitchHttp.request("https://api.betterttv.net/3/cached/emotes/global").GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() == 200
                        ? parseBttvArray(safeArray(response.body()))
                        : Map.<String, EmoteInfo>of())
                .exceptionally(error -> {
                    LOGGER.log(Level.WARNING, "TwitchHUD: BTTV global emote fetch failed", error);
                    return Map.of();
                });
    }

    private CompletableFuture<Map<String, EmoteInfo>> fetchBttvChannel(String broadcasterId) {
        return TwitchHttp.client()
                .sendAsync(TwitchHttp.request("https://api.betterttv.net/3/cached/users/twitch/" + encode(broadcasterId)).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return Map.<String, EmoteInfo>of();
                    }
                    JsonObject root = safeObject(response.body());
                    Map<String, EmoteInfo> result = new LinkedHashMap<>();
                    result.putAll(parseBttvArray(root.getAsJsonArray("channelEmotes")));
                    result.putAll(parseBttvArray(root.getAsJsonArray("sharedEmotes")));
                    return result;
                })
                .exceptionally(error -> {
                    LOGGER.log(Level.WARNING, "TwitchHUD: BTTV channel emote fetch failed", error);
                    return Map.of();
                });
    }

    private static Map<String, EmoteInfo> parseBttvArray(JsonArray array) {
        Map<String, EmoteInfo> result = new LinkedHashMap<>();
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            JsonObject emote = element.getAsJsonObject();
            if (!emote.has("code") || !emote.has("id")) {
                continue;
            }
            String code = emote.get("code").getAsString();
            String id = emote.get("id").getAsString();
            String imageType = string(emote, "imageType");
            String extension = imageType.isBlank() ? "png" : imageType;
            result.put(code, new EmoteInfo("BTTV", id, "https://cdn.betterttv.net/emote/" + id + "/2x." + extension));
        }
        return result;
    }

    private CompletableFuture<Map<String, EmoteInfo>> fetchFfzGlobal() {
        return TwitchHttp.client()
                .sendAsync(TwitchHttp.request("https://api.frankerfacez.com/v1/set/global").GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return Map.<String, EmoteInfo>of();
                    }
                    JsonObject root = safeObject(response.body());
                    JsonArray defaultSets = root.getAsJsonArray("default_sets");
                    JsonObject sets = root.getAsJsonObject("sets");
                    if (defaultSets == null || sets == null) {
                        return Map.<String, EmoteInfo>of();
                    }
                    Map<String, EmoteInfo> result = new LinkedHashMap<>();
                    for (JsonElement id : defaultSets) {
                        result.putAll(parseFfzSet(sets.getAsJsonObject(String.valueOf(id.getAsInt()))));
                    }
                    return result;
                })
                .exceptionally(error -> {
                    LOGGER.log(Level.WARNING, "TwitchHUD: FFZ global emote fetch failed", error);
                    return Map.of();
                });
    }

    private CompletableFuture<Map<String, EmoteInfo>> fetchFfzChannel(String channelLogin) {
        if (channelLogin == null || channelLogin.isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return TwitchHttp.client()
                .sendAsync(TwitchHttp.request("https://api.frankerfacez.com/v1/room/" + encode(channelLogin)).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return Map.<String, EmoteInfo>of();
                    }
                    JsonObject root = safeObject(response.body());
                    JsonObject room = root.getAsJsonObject("room");
                    JsonObject sets = root.getAsJsonObject("sets");
                    if (room == null || sets == null || !room.has("set")) {
                        return Map.<String, EmoteInfo>of();
                    }
                    return parseFfzSet(sets.getAsJsonObject(String.valueOf(room.get("set").getAsInt())));
                })
                .exceptionally(error -> {
                    LOGGER.log(Level.WARNING, "TwitchHUD: FFZ channel emote fetch failed", error);
                    return Map.of();
                });
    }

    private static Map<String, EmoteInfo> parseFfzSet(JsonObject set) {
        Map<String, EmoteInfo> result = new LinkedHashMap<>();
        if (set == null) {
            return result;
        }
        JsonArray emoticons = set.getAsJsonArray("emoticons");
        if (emoticons == null) {
            return result;
        }
        for (JsonElement element : emoticons) {
            JsonObject emote = element.getAsJsonObject();
            if (!emote.has("name") || !emote.has("id") || !emote.has("urls")) {
                continue;
            }
            JsonObject urls = emote.getAsJsonObject("urls");
            String url = urls.has("2") ? urls.get("2").getAsString()
                    : urls.has("1") ? urls.get("1").getAsString() : null;
            if (url == null) {
                continue;
            }
            if (url.startsWith("//")) {
                url = "https:" + url;
            }
            String name = emote.get("name").getAsString();
            String id = String.valueOf(emote.get("id").getAsInt());
            result.put(name, new EmoteInfo("FFZ", id, url));
        }
        return result;
    }

    private static JsonObject safeObject(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException error) {
            return new JsonObject();
        }
    }

    private static JsonArray safeArray(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException error) {
            return new JsonArray();
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
