package com.wolfiy.twitchhud.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class AtomicFiles {
    @FunctionalInterface
    interface OutputWriter {
        void write(OutputStream output) throws IOException;
    }

    private AtomicFiles() {
    }

    static void writeString(
            Path path,
            String content
    ) throws IOException {
        write(
                path,
                output -> output.write(
                        content.getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    static void write(
            Path path,
            OutputWriter writer
    ) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);

        Path temp = Files.createTempFile(
                parent,
                absolute.getFileName().toString(),
                ".tmp"
        );
        boolean moved = false;
        try {
            try (OutputStream output =
                         Files.newOutputStream(temp)) {
                writer.write(output);
            }

            try {
                Files.move(
                        temp,
                        absolute,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(
                        temp,
                        absolute,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }
}
