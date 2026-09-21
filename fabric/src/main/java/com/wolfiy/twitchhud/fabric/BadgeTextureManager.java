package com.wolfiy.twitchhud.fabric;

import com.wolfiy.twitchhud.core.BadgeIconService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class BadgeTextureManager {
    private static final long RETRY_DELAY_MS = 30_000L;

    public record BadgeTexture(Identifier id, int width, int height) {
    }

    private final BadgeIconService badgeIconService;
    private final Map<String, BadgeTexture> ready = new ConcurrentHashMap<>();
    private final Map<String, Integer> readyAtVersion =
            new ConcurrentHashMap<>();
    private final Map<String, Boolean> pending = new ConcurrentHashMap<>();
    private final Map<String, Integer> missingAtVersion =
            new ConcurrentHashMap<>();
    private final Map<String, Long> retryAfter =
            new ConcurrentHashMap<>();

    public BadgeTextureManager(BadgeIconService badgeIconService) {
        this.badgeIconService = badgeIconService;
    }

    public Optional<BadgeTexture> get(String setId, String version) {
        if (!badgeIconService.isEnabled()) {
            return Optional.empty();
        }
        String key = setId + ":" + version;
        int metadataVersion =
                badgeIconService.metadataVersion();
        BadgeTexture existing = ready.get(key);
        Integer readyVersion = readyAtVersion.get(key);
        if (existing != null
                && readyVersion != null
                && readyVersion == metadataVersion) {
            return Optional.of(existing);
        }
        if (existing != null) {
            ready.remove(key);
            readyAtVersion.remove(key);
        }

        Long retryAt = retryAfter.get(key);
        if (retryAt != null
                && System.currentTimeMillis() < retryAt) {
            return Optional.empty();
        }

        Integer missingVersion =
                missingAtVersion.get(key);
        if (missingVersion != null
                && missingVersion == metadataVersion) {
            return Optional.empty();
        }

        if (pending.putIfAbsent(key, Boolean.TRUE) == null) {
            badgeIconService.iconUrl(setId, version)
                    .ifPresentOrElse(
                            url -> badgeIconService
                                    .imageCache()
                                    .fetch(url)
                                    .thenAccept(
                                            bytes -> upload(
                                                    key,
                                                    bytes,
                                                    metadataVersion
                                            )
                                    ),
                            () -> {
                                missingAtVersion.put(
                                        key,
                                        metadataVersion
                                );
                                pending.remove(key);
                            }
                    );
        }
        return Optional.empty();
    }

    private void upload(
            String key,
            byte[] bytes,
            int metadataVersion
    ) {
        if (bytes.length == 0) {
            retryAfter.put(
                    key,
                    System.currentTimeMillis() + RETRY_DELAY_MS
            );
            pending.remove(key);
            return;
        }
        NativeImage image;
        try {
            image = TextureDecoder.decode(bytes);
        } catch (IOException error) {
            retryAfter.put(
                    key,
                    System.currentTimeMillis() + RETRY_DELAY_MS
            );
            pending.remove(key);
            return;
        }
        if (image == null) {
            retryAfter.put(
                    key,
                    System.currentTimeMillis() + RETRY_DELAY_MS
            );
            pending.remove(key);
            return;
        }

        MinecraftClient.getInstance().execute(() -> {
            NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
            Identifier id = Identifier.of("twitchhud", "badge_" + key.replaceAll("[^a-z0-9_]", "_"));
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
            ready.put(
                    key,
                    new BadgeTexture(
                            id,
                            image.getWidth(),
                            image.getHeight()
                    )
            );
            readyAtVersion.put(key, metadataVersion);
            missingAtVersion.remove(key);
            retryAfter.remove(key);
            pending.remove(key);
        });
    }
}
