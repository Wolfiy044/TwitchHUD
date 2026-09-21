package com.wolfiy.twitchhud.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

public final class TwitchHttp {
    private static final Duration CONNECT_TIMEOUT =
            Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(8);
    private static final HttpClient CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();

    private TwitchHttp() {
    }

    public static HttpClient client() {
        return CLIENT;
    }

    public static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "TwitchHUD/0.1");
    }
}
