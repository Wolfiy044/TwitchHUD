package com.wolfiy.twitchhud.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TwitchOAuthClient {
    private static final Logger LOGGER =
            Logger.getLogger("TwitchHUD");

    public record DeviceAuthorization(
            String verificationUri,
            String userCode
    ) {
    }

    public record UserToken(
            String accessToken,
            String userId,
            List<String> scopes
    ) {
        public boolean hasScope(String scope) {
            return scope == null
                    || scope.isBlank()
                    || scopes.contains(scope);
        }
    }

    private record TokenInfo(
            String userId,
            List<String> scopes
    ) {
    }

    private record DeviceFlow(
            String deviceCode,
            String userCode,
            String verificationUri,
            long expiresAt,
            int intervalSeconds
    ) {
    }

    private final TwitchCredentials credentials;
    private final TwitchOAuthTokenStore tokenStore;
    private final Runnable onTokenReady;

    private volatile boolean active;
    private volatile boolean authorizationInProgress;
    private volatile String authorizationUrl;
    private volatile String accessToken;
    private volatile String refreshToken;

    TwitchOAuthClient(
            TwitchCredentials credentials,
            Path configDir,
            Runnable onTokenReady
    ) {
        this.credentials = credentials;
        this.tokenStore =
                new TwitchOAuthTokenStore(configDir);
        this.onTokenReady = onTokenReady;

        TwitchOAuthTokenStore.Tokens tokens =
                tokenStore.load();
        accessToken = tokens.accessToken();
        refreshToken = tokens.refreshToken();
    }

    public boolean isAvailable() {
        return credentials != null;
    }

    private synchronized void applyTokens(
            String nextAccessToken,
            String nextRefreshToken
    ) {
        accessToken = nextAccessToken;
        refreshToken = nextRefreshToken;
        tokenStore.save(accessToken, refreshToken);
    }

    public boolean hasStoredToken() {
        return accessToken != null
                && !accessToken.isBlank();
    }

    public boolean authorizationInProgress() {
        return authorizationInProgress;
    }

    String clientId() {
        return credentials == null
                ? ""
                : credentials.clientId();
    }

    public String authorizationUrl() {
        return authorizationUrl;
    }

    void resume() {
        active = true;
    }

    void cancel() {
        active = false;
        authorizationInProgress = false;
        authorizationUrl = null;
    }

    public CompletableFuture<DeviceAuthorization>
            beginAuthorization(String scope) {
        if (credentials == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (authorizationInProgress
                && authorizationUrl == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (authorizationInProgress) {
            return CompletableFuture.completedFuture(
                    new DeviceAuthorization(
                            authorizationUrl,
                            ""
                    )
            );
        }

        active = true;
        authorizationInProgress = true;
        CompletableFuture<DeviceAuthorization> result =
                new CompletableFuture<>();

        startWorker(() -> authorize(scope, result));
        return result;
    }

    UserToken ensureUserToken(String requiredScope) {
        if (!isAvailable() || !hasStoredToken()) {
            return null;
        }

        TokenInfo info = validateToken(accessToken);
        if (info == null && refreshUserToken()) {
            info = validateToken(accessToken);
        }
        if (info == null
                || info.userId().isBlank()
                || !hasScopes(info.scopes(), requiredScope)) {
            return null;
        }

        return new UserToken(
                accessToken,
                info.userId(),
                List.copyOf(info.scopes())
        );
    }

    private void authorize(
            String scope,
            CompletableFuture<DeviceAuthorization> result
    ) {
        try {
            DeviceFlow flow = requestDeviceCode(scope);
            if (flow == null) {
                finishAuthorization();
                result.complete(null);
                return;
            }

            authorizationUrl = flow.verificationUri();
            result.complete(new DeviceAuthorization(
                    flow.verificationUri(),
                    flow.userCode()
            ));
            pollDeviceToken(flow, scope);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            finishAuthorization();
            result.complete(null);
        } catch (Exception error) {
            finishAuthorization();
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: OAuth authorization failed",
                    error
            );
            result.complete(null);
        }
    }

    private DeviceFlow requestDeviceCode(String scope)
            throws IOException, InterruptedException {
        String body = form(
                "client_id",
                credentials.clientId(),
                "scopes",
                scope
        );
        HttpRequest request = TwitchHttp.request(
                        "https://id.twitch.tv/oauth2/device"
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

        HttpResponse<String> response = TwitchHttp.client().send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 200) {
            LOGGER.warning(
                    "TwitchHUD: device authorization failed with "
                            + response.statusCode()
            );
            return null;
        }

        JsonObject json = JsonParser.parseString(
                response.body()
        ).getAsJsonObject();
        long expiresAt = System.currentTimeMillis()
                + json.get("expires_in").getAsLong() * 1000L;
        int interval = json.has("interval")
                ? json.get("interval").getAsInt()
                : 5;

        return new DeviceFlow(
                json.get("device_code").getAsString(),
                json.get("user_code").getAsString(),
                json.get("verification_uri").getAsString(),
                expiresAt,
                Math.max(1, interval)
        );
    }

    private void pollDeviceToken(
            DeviceFlow flow,
            String scope
    ) throws InterruptedException {
        int interval = flow.intervalSeconds();

        while (active
                && System.currentTimeMillis()
                < flow.expiresAt()) {
            Thread.sleep(interval * 1000L);

            try {
                HttpResponse<String> response =
                        requestDeviceToken(
                                flow.deviceCode(),
                                scope
                        );
                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(
                            response.body()
                    ).getAsJsonObject();
                    applyTokens(
                            string(json, "access_token"),
                            string(json, "refresh_token")
                    );
                    finishAuthorization();
                    onTokenReady.run();
                    return;
                }

                JsonObject error = safeObject(
                        response.body()
                );
                String message = string(error, "message");
                if ("authorization_pending".equals(message)) {
                    continue;
                }
                if ("slow_down".equals(message)) {
                    interval += 5;
                    continue;
                }

                finishAuthorization();
                return;
            } catch (IOException error) {
                finishAuthorization();
                LOGGER.log(
                        Level.WARNING,
                        "TwitchHUD: device token request failed",
                        error
                );
                return;
            }
        }

        finishAuthorization();
    }

    private HttpResponse<String> requestDeviceToken(
            String deviceCode,
            String scope
    ) throws IOException, InterruptedException {
        String body = form(
                "client_id",
                credentials.clientId(),
                "scopes",
                scope,
                "device_code",
                deviceCode,
                "grant_type",
                "urn:ietf:params:oauth:grant-type:device_code"
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

        return TwitchHttp.client().send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private TokenInfo validateToken(String token) {
        HttpRequest request = TwitchHttp.request(
                        "https://id.twitch.tv/oauth2/validate"
                )
                .header(
                        "Authorization",
                        "OAuth " + token
                )
                .GET()
                .build();

        try {
            HttpResponse<String> response = TwitchHttp.client().send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                return null;
            }

            JsonObject json = JsonParser.parseString(
                    response.body()
            ).getAsJsonObject();
            if (!credentials.clientId().equals(
                    string(json, "client_id")
            )) {
                return null;
            }

            JsonArray array = json.has("scopes")
                    ? json.getAsJsonArray("scopes")
                    : new JsonArray();
            List<String> scopes = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                scopes.add(
                        array.get(i).getAsString()
                );
            }

            return new TokenInfo(
                    string(json, "user_id"),
                    List.copyOf(scopes)
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: token validation failed",
                    error
            );
            return null;
        }
    }

    private boolean refreshUserToken() {
        if (refreshToken == null
                || refreshToken.isBlank()) {
            return false;
        }

        String body = form(
                "grant_type",
                "refresh_token",
                "refresh_token",
                refreshToken,
                "client_id",
                credentials.clientId()
        );
        if (credentials.hasClientSecret()) {
            body += "&client_secret="
                    + encode(credentials.clientSecret());
        }

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

        try {
            HttpResponse<String> response = TwitchHttp.client().send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                return false;
            }

            JsonObject json = JsonParser.parseString(
                    response.body()
            ).getAsJsonObject();
            String nextAccess = string(json, "access_token");
            String nextRefresh = string(json, "refresh_token");
            applyTokens(
                    nextAccess,
                    nextRefresh.isBlank() ? refreshToken : nextRefresh
            );
            return !accessToken.isBlank();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: token refresh failed",
                    error
            );
            return false;
        }
    }

    private void finishAuthorization() {
        authorizationInProgress = false;
        authorizationUrl = null;
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

    private static JsonObject safeObject(String json) {
        try {
            return JsonParser.parseString(
                    json
            ).getAsJsonObject();
        } catch (RuntimeException error) {
            return new JsonObject();
        }
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

    private static void startWorker(Runnable task) {
        Thread thread = new Thread(
                task,
                "twitchhud-oauth"
        );
        thread.setDaemon(true);
        thread.start();
    }
}
