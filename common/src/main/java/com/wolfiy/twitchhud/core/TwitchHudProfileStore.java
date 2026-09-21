package com.wolfiy.twitchhud.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TwitchHudProfileStore {
    private static final Logger LOGGER =
            Logger.getLogger("TwitchHUD");
    private final Path profilesDir;
    private final Path exportPath;

    public TwitchHudProfileStore(Path configDir) {
        profilesDir = configDir.resolve("twitchhud-profiles");
        exportPath = configDir.resolve("twitchhud-export.json");
    }

    public List<String> profiles() {
        if (!Files.isDirectory(profilesDir)) {
            return List.of();
        }
        try (var stream = Files.list(profilesDir)) {
            List<String> names = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString())
                    .map(name -> name.substring(0, name.length() - 5))
                    .sorted(Comparator.naturalOrder())
                    .forEach(names::add);
            return names;
        } catch (IOException error) {
            LOGGER.log(Level.WARNING, "TwitchHUD: profile list failed", error);
            return List.of();
        }
    }

    public boolean saveProfile(String name, TwitchHudConfig config) {
        String safe = safeName(name);
        if (safe.isBlank()) {
            return false;
        }
        return write(profilesDir.resolve(safe + ".json"), config.toJson());
    }

    public Optional<TwitchHudConfig> loadProfile(String name) {
        String safe = safeName(name);
        if (safe.isBlank()) {
            return Optional.empty();
        }
        return read(profilesDir.resolve(safe + ".json"));
    }

    public boolean deleteProfile(String name) {
        String safe = safeName(name);
        if (safe.isBlank()) {
            return false;
        }
        try {
            return Files.deleteIfExists(profilesDir.resolve(safe + ".json"));
        } catch (IOException error) {
            LOGGER.log(Level.WARNING, "TwitchHUD: profile delete failed", error);
            return false;
        }
    }

    public boolean exportConfig(TwitchHudConfig config) {
        return write(exportPath, config.toJson());
    }

    public Optional<TwitchHudConfig> importConfig() {
        return read(exportPath);
    }

    public Path exportPath() {
        return exportPath;
    }

    private Optional<TwitchHudConfig> read(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return Optional.ofNullable(TwitchHudConfig.fromJson(json));
        } catch (IOException | RuntimeException error) {
            LOGGER.log(Level.WARNING, "TwitchHUD: config import failed", error);
            return Optional.empty();
        }
    }

    private boolean write(Path path, String content) {
        try {
            AtomicFiles.writeString(path, content);
            return true;
        } catch (IOException error) {
            LOGGER.log(Level.WARNING, "TwitchHUD: config export failed", error);
            return false;
        }
    }

    private static String safeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim()
                .replaceAll("[^a-zA-Z0-9 _.-]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
