package com.pzlauncher.zabric.hook.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds mod jars that hold only hook listings. */
final class Listings {
    private Listings() {
    }

    /** A jar with one listing per pair of hook class and listing text. */
    static Path jar(Path dir, String name, String... hookClassAndText) throws IOException {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < hookClassAndText.length; i += 2) {
                zip.putNextEntry(new ZipEntry(HookListing.DIR + hookClassAndText[i] + HookListing.SUFFIX));
                zip.write(hookClassAndText[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    /** {@code hooks}: owner, method and descriptor; {@code patch} hooks each. */
    static String text(String modId, String gate, String patch, String... hooks) {
        StringBuilder text = new StringBuilder(HookListing.HEADER + "\nmod " + modId + "\ngate " + gate + "\npatch "
                + patch + "\n");
        for (String hook : hooks) {
            text.append("hook ").append(hook).append(' ').append(patch).append('\n');
        }
        return text.toString();
    }
}
