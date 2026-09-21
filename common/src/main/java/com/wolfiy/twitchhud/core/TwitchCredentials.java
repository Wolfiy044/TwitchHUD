package com.wolfiy.twitchhud.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public record TwitchCredentials(
        String clientId,
        String clientSecret
) {
    private static final Logger LOGGER =
            Logger.getLogger("TwitchHUD");
    public static final String FILE_NAME =
            "twitchhud-secrets.properties";

    public static TwitchCredentials load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return null;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: credential load failed",
                    error
            );
            return null;
        }

        String clientId = props.getProperty(
                "clientId",
                ""
        ).trim();
        String clientSecret = props.getProperty(
                "clientSecret",
                ""
        ).trim();

        if (clientId.isEmpty()) {
            return null;
        }
        return new TwitchCredentials(
                clientId,
                clientSecret
        );
    }

    public boolean hasClientSecret() {
        return clientSecret != null
                && !clientSecret.isBlank();
    }
}
