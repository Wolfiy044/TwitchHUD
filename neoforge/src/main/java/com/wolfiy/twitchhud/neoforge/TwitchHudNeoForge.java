package com.wolfiy.twitchhud.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.wolfiy.twitchhud.core.TwitchCredentials;
import com.wolfiy.twitchhud.core.TwitchHudConfig;
import com.wolfiy.twitchhud.core.TwitchHudSession;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

@Mod(TwitchHudNeoForge.MOD_ID)
public final class TwitchHudNeoForge {
    public static final String MOD_ID = "twitchhud";

    private static Path configPath;
    private static TwitchHudConfig config;
    private static TwitchHudSession session;
    private static ChatOverlayRenderer renderer;
    private static KeyMapping openConfigKey;
    private static KeyMapping openChatKey;

    public TwitchHudNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterGuiLayers);
        modEventBus.addListener(this::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onScreenRender);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        Path configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        configPath = configDir.resolve("twitchhud.json");
        config = TwitchHudConfig.load(configPath);
        TwitchCredentials credentials = TwitchCredentials.load(configDir);
        session = new TwitchHudSession(config, credentials, configDir);
        renderer = new ChatOverlayRenderer(session);
        session.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (session != null) {
                session.stop();
            }
            if (config != null && configPath != null) {
                config.save(configPath);
            }
        }, "twitchhud-shutdown"));
    }

    private void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MOD_ID, "twitchhud_overlay"), (graphics, deltaTracker) -> {
            if (renderer == null) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            var screen = client.screen;
            if (screen == null && !client.options.hideGui) {
                renderer.render(graphics);
            }
        });
    }

    private void onScreenRender(ScreenEvent.Render.Post event) {
        if (renderer == null
                || config == null
                || !config.showOutsideGame()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui) {
            return;
        }

        var screen = event.getScreen();
        if (screen instanceof TwitchHudConfigScreen
                || screen instanceof TwitchHudChatScreen) {
            return;
        }
        renderer.render(event.getGuiGraphics());
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        openConfigKey = new KeyMapping("key.twitchhud.open_config", InputConstants.Type.KEYSYM,
                InputConstants.KEY_K, "category.twitchhud");
        event.register(openConfigKey);
        openChatKey = new KeyMapping("key.twitchhud.open_chat", InputConstants.Type.KEYSYM,
                InputConstants.KEY_L, "category.twitchhud");
        event.register(openChatKey);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        if (session == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        while (openConfigKey != null && openConfigKey.consumeClick()) {
            if (client.screen == null) {
                client.setScreen(new TwitchHudConfigScreen(null, session, renderer, configPath));
            }
        }
        while (openChatKey != null && openChatKey.consumeClick()) {
            if (client.screen == null) {
                client.setScreen(new TwitchHudChatScreen(session, renderer));
            }
        }
    }

    public static TwitchHudSession session() {
        return session;
    }

    public static Path configPath() {
        return configPath;
    }
}
