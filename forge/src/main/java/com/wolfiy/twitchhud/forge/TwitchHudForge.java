package com.wolfiy.twitchhud.forge;

import com.mojang.blaze3d.platform.InputConstants;
import com.wolfiy.twitchhud.core.TwitchCredentials;
import com.wolfiy.twitchhud.core.TwitchHudConfig;
import com.wolfiy.twitchhud.core.TwitchHudSession;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.nio.file.Path;

@Mod(TwitchHudForge.MOD_ID)
public final class TwitchHudForge {
    public static final String MOD_ID = "twitchhud";

    private static Path configPath;
    private static TwitchHudConfig config;
    private static TwitchHudSession session;
    private static ChatOverlayRenderer renderer;
    private static KeyMapping openConfigKey;
    private static KeyMapping openChatKey;

    public TwitchHudForge(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onAddOverlayLayers);
        modEventBus.addListener(this::onRegisterKeyMappings);
        MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(this::onScreenRender);
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

    private void onAddOverlayLayers(AddGuiOverlayLayersEvent event) {
        event.getLayeredDraw().add(ResourceLocation.fromNamespaceAndPath(MOD_ID, "twitchhud_overlay"), (graphics, deltaTracker) -> {
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

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || session == null) {
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
