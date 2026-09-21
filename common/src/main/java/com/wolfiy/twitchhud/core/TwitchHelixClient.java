package com.wolfiy.twitchhud.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TwitchHelixClient {
    public record AppAuth(
            String clientId,
            String accessToken
    ) {
        public boolean ready() {
            return clientId != null
                    && !clientId.isBlank()
                    && accessToken != null
                    && !accessToken.isBlank();
        }
    }

    private static final Logger LOGGER =
            Logger.getLogger("TwitchHUD");

    private final TwitchCredentials credentials;
    private final AtomicReference<String> appAccessToken =
            new AtomicReference<>();

    private volatile long tokenExpiresAtMillis;
    private CompletableFuture<String> tokenRequest;

    public TwitchHelixClient(TwitchCredentials credentials) {
        this.credentials = credentials;
    }

    public boolean isConfigured() {
        return credentials != null
                && credentials.hasClientSecret();
    }

    private synchronized CompletableFuture<String> ensureToken() {
        String existing = appAccessToken.get();
        if (existing != null
                && !existing.isBlank()
                && System.currentTimeMillis()
                        < tokenExpiresAtMillis) {
            return CompletableFuture.completedFuture(existing);
        }

        if (tokenRequest != null
                && !tokenRequest.isDone()) {
            return tokenRequest;
        }

        String body = form(
                "client_id",
                credentials.clientId(),
                "client_secret",
                credentials.clientSecret(),
                "grant_type",
                "client_credentials"
        );
        HttpRequest request = TwitchHttp.request(
                        "https://id.twitch.tv/oauth2/token"
                )
                .header(
                        "Content-Type",
                        "application/x-www-form-urlencoded"
                )
                .POST(HttpRequest.BodyPublishers.ofString(
                        body,
                        StandardCharsets.UTF_8
                ))
                .build();

        CompletableFuture<String> created =
                TwitchHttp.client()
                        .sendAsync(
                                request,
                                HttpResponse.BodyHandlers.ofString()
                        )
                        .thenApply(response -> {
                            if (response.statusCode() != 200) {
                                LOGGER.warning(
                                        "TwitchHUD: app token request "
                                                + "failed with HTTP "
                                                + response.statusCode()
                                );
                                return null;
                            }

                            JsonObject json =
                                    JsonParser.parseString(
                                            response.body()
                                    ).getAsJsonObject();
                            String token =
                                    string(json, "access_token");
                            long expiresIn =
                                    number(json, "expires_in");
                            if (token.isBlank()) {
                                return null;
                            }

                            appAccessToken.set(token);
                            long safeLifetime =
                                    Math.max(30, expiresIn - 60);
                            tokenExpiresAtMillis =
                                    System.currentTimeMillis()
                                            + safeLifetime * 1000L;
                            return token;
                        })
                        .exceptionally(error -> {
                            LOGGER.log(
                                    Level.WARNING,
                                    "TwitchHUD: app token request failed",
                                    error
                            );
                            return null;
                        });

        tokenRequest = created;
        created.whenComplete(
                (token, error) -> clearTokenRequest(created)
        );
        return created;
    }

    private synchronized void clearTokenRequest(
            CompletableFuture<String> request
    ) {
        if (tokenRequest == request) {
            tokenRequest = null;
        }
    }

    public AppAuth appAuth() {
        if (!isConfigured()) {
            return null;
        }

        try {
            String token = ensureToken().join();
            if (token == null || token.isBlank()) {
                return null;
            }
            return new AppAuth(
                    credentials.clientId(),
                    token
            );
        } catch (RuntimeException error) {
            LOGGER.log(
                    Level.FINE,
                    "TwitchHUD: app token unavailable",
                    error
            );
            return null;
        }
    }

    public CompletableFuture<Map<String, Map<String, String>>>
            fetchGlobalBadges() {
        if (!isConfigured()) {
            return fetchPublicBadges(
                    "https://api.ivr.fi/v2/twitch/badges/global"
            );
        }
        return fetchBadges(
                "https://api.twitch.tv/helix/chat/badges/global"
        );
    }

    public CompletableFuture<Map<String, Map<String, String>>>
            fetchChannelBadges(String broadcasterId) {
        if (!isConfigured()) {
            return fetchPublicBadges(
                    "https://api.ivr.fi/v2/twitch/badges/channel?id="
                            + encode(broadcasterId)
            );
        }
        return fetchBadges(
                "https://api.twitch.tv/helix/chat/badges?broadcaster_id="
                        + encode(broadcasterId)
        );
    }

    private CompletableFuture<Map<String, Map<String, String>>>
            fetchPublicBadges(String url) {
        HttpRequest request = TwitchHttp.request(url)
                .GET()
                .build();
        return TwitchHttp.client()
                .sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                )
                .thenApply(this::parseBadgeResponse)
                .exceptionally(error -> {
                    LOGGER.log(
                            Level.WARNING,
                            "TwitchHUD: public badge fetch failed",
                            error
                    );
                    return Map.of();
                });
    }

    private CompletableFuture<Map<String, Map<String, String>>>
            fetchBadges(String url) {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        return ensureToken().thenCompose(token -> {
            if (token == null || token.isBlank()) {
                return CompletableFuture.completedFuture(
                        Map.<String, Map<String, String>>of()
                );
            }

            HttpRequest request = TwitchHttp.request(url)
                    .header(
                            "Client-Id",
                            credentials.clientId()
                    )
                    .header(
                            "Authorization",
                            "Bearer " + token
                    )
                    .GET()
                    .build();
            return TwitchHttp.client()
                    .sendAsync(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    )
                    .thenApply(this::parseBadgeResponse)
                    .exceptionally(error -> {
                        LOGGER.log(
                                Level.WARNING,
                                "TwitchHUD: badge fetch failed",
                                error
                        );
                        return Map.of();
                    });
        });
    }

    private Map<String, Map<String, String>>
            parseBadgeResponse(
                    HttpResponse<String> response
            ) {
        Map<String, Map<String, String>> result =
                new HashMap<>();
        if (response.statusCode() != 200) {
            LOGGER.warning(
                    "TwitchHUD: badge API returned HTTP "
                            + response.statusCode()
            );
            return result;
        }

        try {
            JsonElement json = JsonParser.parseString(
                    response.body()
            );
            JsonArray data;
            if (json.isJsonArray()) {
                data = json.getAsJsonArray();
            } else if (json.isJsonObject()
                    && json.getAsJsonObject().has("data")
                    && json.getAsJsonObject()
                            .get("data").isJsonArray()) {
                data = json.getAsJsonObject()
                        .getAsJsonArray("data");
            } else {
                return result;
            }

            for (JsonElement element : data) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject setObj = element.getAsJsonObject();
                String setId = string(setObj, "set_id");
                if (setId.isBlank()
                        || !setObj.has("versions")
                        || !setObj.get("versions").isJsonArray()) {
                    continue;
                }

                Map<String, String> versions =
                        new HashMap<>();
                for (JsonElement versionElement
                        : setObj.getAsJsonArray("versions")) {
                    if (!versionElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject versionObj =
                            versionElement.getAsJsonObject();
                    String versionId =
                            string(versionObj, "id");
                    String imageUrl =
                            string(
                                    versionObj,
                                    "image_url_2x"
                            );
                    if (!versionId.isBlank()
                            && !imageUrl.isBlank()) {
                        versions.put(versionId, imageUrl);
                    }
                }
                result.put(setId, versions);
            }
        } catch (RuntimeException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: invalid badge response",
                    error
            );
        }
        return result;
    }

    private static String form(String... values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (!result.isEmpty()) {
                result.append('&');
            }
            result.append(encode(values[i]))
                    .append('=')
                    .append(encode(values[i + 1]));
        }
        return result.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(
                value == null ? "" : value,
                StandardCharsets.UTF_8
        );
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

    private static long number(
            JsonObject object,
            String key
    ) {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException error) {
            return 0L;
        }
    }
}
