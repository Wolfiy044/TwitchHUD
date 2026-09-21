package com.wolfiy.twitchhud.fabric;

import com.wolfiy.twitchhud.core.TwitchCredentials;
import com.wolfiy.twitchhud.core.TwitchHudConfig;
import com.wolfiy.twitchhud.core.TwitchHudSession;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

public final class TwitchHudFabric implements ClientModInitializer {
    private static Path configPath;
    private static TwitchHudConfig config;
    private static TwitchHudSession session;
    private static ChatOverlayRenderer renderer;
    private static boolean openConfigKeyWasDown;
    private static boolean openChatKeyWasDown;

    @Override
    public void onInitializeClient() {
        Path configDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("config");
        configPath = configDir.resolve("twitchhud.json");
        config = TwitchHudConfig.load(configPath);
        TwitchCredentials credentials = TwitchCredentials.load(configDir);
        session = new TwitchHudSession(config, credentials, configDir);
        renderer = new ChatOverlayRenderer(session);
        session.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            session.stop();
            config.save(configPath);
        }, "twitchhud-shutdown"));
    }

    public static void onHudRender(DrawContext context) {
        if (renderer == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        long windowHandle = client.getWindow().getHandle();

        boolean configKeyDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_K) == GLFW.GLFW_PRESS;
        if (configKeyDown && !openConfigKeyWasDown && client.currentScreen == null) {
            client.setScreen(new TwitchHudConfigScreen(null, session, renderer, configPath));
        }
        openConfigKeyWasDown = configKeyDown;

        boolean chatKeyDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_L) == GLFW.GLFW_PRESS;
        if (chatKeyDown && !openChatKeyWasDown && client.currentScreen == null) {
            client.setScreen(new TwitchHudChatScreen(session, renderer));
        }
        openChatKeyWasDown = chatKeyDown;

        if (client.options.hudHidden || client.currentScreen != null) {
            return;
        }
        renderer.render(context);
    }

    public static void onScreenRender(DrawContext context) {
        if (renderer == null || config == null
                || !config.showOutsideGame()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden
                || client.currentScreen instanceof TwitchHudConfigScreen
                || client.currentScreen instanceof TwitchHudChatScreen) {
            return;
        }
        renderer.render(context);
    }

    public static TwitchHudSession session() {
        return session;
    }
}
