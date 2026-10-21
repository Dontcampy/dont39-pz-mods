package com.pzlauncher.zabric.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class Folders {
    private Folders() {
    }

    /** The folders directly in {@code dir}, sorted. */
    public static List<Path> children(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isDirectory).sorted().toList();
        }
    }
}
