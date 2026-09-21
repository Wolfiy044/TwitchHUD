package com.wolfiy.twitchhud.fabric;

import com.wolfiy.twitchhud.core.EmoteCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class EmoteTextureManager {
    private static final long RETRY_DELAY_MS = 10_000L;
    public record EmoteTexture(Identifier id, int width, int height) {
    }

    private final EmoteCache emoteCache;
    private final Map<String, EmoteTexture> ready = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAfter =
            new ConcurrentHashMap<>();

    public EmoteTextureManager(EmoteCache emoteCache) {
        this.emoteCache = emoteCache;
    }

    public Optional<EmoteTexture> get(String emoteId, String imageUrl) {
        EmoteTexture existing = ready.get(emoteId);
        if (existing != null) {
            return Optional.of(existing);
        }
        Long retryAt = retryAfter.get(emoteId);
        if (retryAt != null
                && System.currentTimeMillis() < retryAt) {
            return Optional.empty();
        }
        if (pending.putIfAbsent(emoteId, Boolean.TRUE) == null) {
            emoteCache.fetch(emoteId, imageUrl).thenAccept(bytes -> upload(emoteId, bytes));
        }
        return Optional.empty();
    }

    private void upload(String emoteId, byte[] bytes) {
        if (bytes.length == 0) {
            retryAfter.put(
                    emoteId,
                    System.currentTimeMillis() + RETRY_DELAY_MS
            );
            pending.remove(emoteId);
            return;
        }
        NativeImage image;
        try {
            image = TextureDecoder.decode(bytes);
        } catch (IOException error) {
            retryAfter.put(
                    emoteId,
                    System.currentTimeMillis() + RETRY_DELAY_MS
            );
            pending.remove(emoteId);
            return;
        }
        if (image == null) {
            retryAfter.put(
                    emoteId,
                    System.currentTimeMillis() + RETRY_DELAY_MS
            );
            pending.remove(emoteId);
            return;
        }

        MinecraftClient.getInstance().execute(() -> {
            Identifier id = Identifier.of("twitchhud", "emote_" + emoteId.replaceAll("[^a-z0-9_]", "_"));
            NativeImageBackedTexture texture = new NativeImageBackedTexture(id::toString, image);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
            ready.put(
                    emoteId,
                    new EmoteTexture(
                            id,
                            image.getWidth(),
                            image.getHeight()
                    )
            );
            retryAfter.remove(emoteId);
            pending.remove(emoteId);
        });
    }
}
