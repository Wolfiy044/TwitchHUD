package com.wolfiy.twitchhud.fabric;

import com.wolfiy.twitchhud.core.TwitchCredentials;
import com.wolfiy.twitchhud.core.TwitchHudConfig;
import com.wolfiy.twitchhud.core.TwitchHudSession;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
        Path configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
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

    public static void onHudRender(GuiGraphicsExtractor context) {
        if (renderer == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        long windowHandle = client.getWindow().handle();

        boolean configKeyDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_K) == GLFW.GLFW_PRESS;
        if (configKeyDown && !openConfigKeyWasDown && client.gui.screen() == null) {
            client.gui.setScreen(new TwitchHudConfigScreen(null, session, renderer, configPath));
        }
        openConfigKeyWasDown = configKeyDown;

        boolean chatKeyDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_L) == GLFW.GLFW_PRESS;
        if (chatKeyDown && !openChatKeyWasDown && client.gui.screen() == null) {
            client.gui.setScreen(new TwitchHudChatScreen(session, renderer));
        }
        openChatKeyWasDown = chatKeyDown;

        if (client.gui.hud.isHidden() || client.gui.screen() != null) {
            return;
        }
        renderer.render(context);
    }

    public static void onScreenRender(GuiGraphicsExtractor context) {
        if (renderer == null || config == null
                || !config.showOutsideGame()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.gui.hud.isHidden()
                || client.gui.screen() instanceof TwitchHudConfigScreen
                || client.gui.screen() instanceof TwitchHudChatScreen) {
            return;
        }
        renderer.render(context);
    }

    public static TwitchHudSession session() {
        return session;
    }
}
