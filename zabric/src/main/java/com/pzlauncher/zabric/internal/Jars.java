package com.pzlauncher.zabric.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Jars {
    private Jars() {
    }

    /** The jar or class folder {@code c} was loaded from. */
    public static Path codeSource(Class<?> c) throws URISyntaxException {
        CodeSource source = c.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException(c.getName() + " has no code source");
        }
        return Path.of(source.getLocation().toURI());
    }

    public static byte[] bytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    public static List<String> lines(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).lines().toList();
    }

    public static List<String> lines(ZipFile zip, ZipEntry entry) throws IOException {
        return lines(bytes(zip, entry));
    }
}
