package com.wolfiy.twitchhud.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

final class TwitchOAuthTokenStore {
    private static final Logger LOGGER =
            Logger.getLogger("TwitchHUD");
    private final Path path;

    TwitchOAuthTokenStore(Path configDir) {
        path = configDir.resolve(
                "twitchhud-oauth.properties"
        );
    }

    Tokens load() {
        if (!Files.exists(path)) {
            return new Tokens("", "");
        }

        Properties properties = new Properties();
        try (InputStream input =
                     Files.newInputStream(path)) {
            properties.load(input);
            return new Tokens(
                    properties.getProperty(
                            "accessToken",
                            ""
                    ).trim(),
                    properties.getProperty(
                            "refreshToken",
                            ""
                    ).trim()
            );
        } catch (IOException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: OAuth token load failed",
                    error
            );
            return new Tokens("", "");
        }
    }

    synchronized void save(
            String accessToken,
            String refreshToken
    ) {
        Properties properties = new Properties();
        properties.setProperty(
                "accessToken",
                accessToken
        );
        properties.setProperty(
                "refreshToken",
                refreshToken
        );

        try {
            AtomicFiles.write(
                    path,
                    output -> properties.store(
                            output,
                            "TwitchHUD OAuth"
                    )
            );
        } catch (IOException error) {
            LOGGER.log(
                    Level.WARNING,
                    "TwitchHUD: OAuth token save failed",
                    error
            );
        }
    }

    record Tokens(
            String accessToken,
            String refreshToken
    ) {
    }
}
