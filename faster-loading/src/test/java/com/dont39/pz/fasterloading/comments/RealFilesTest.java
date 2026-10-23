package com.dont39.pz.fasterloading.comments;

import org.junit.jupiter.api.Test;
import zombie.scripting.ScriptParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Comments#strip} against the game's method on the game's and the installed Workshop mods' script-format
 * files, as the build finds them ({@code pzDir}); a folder that is not there is skipped.
 */
class RealFilesTest {
    // the other files the game strips comments from besides media/scripts
    private static final Set<String> NAMES = Set.of("tilegeometry.txt", "spritemodels.txt", "seating.txt",
            "seams.txt", "perks.txt", "sandbox-options.txt", "fonts.txt");

    @Test
    void theGamesFiles() {
        Path media = folder("fasterloading.gameDir").resolve("media");
        assumeTrue(Files.isDirectory(media), media + " is not there");
        int compared = compare(media);
        // the game ships about a thousand script files
        assertTrue(compared > 500, "only " + compared + " files under " + media);
    }

    @Test
    void theWorkshopModsFiles() {
        Path workshop = folder("fasterloading.workshop");
        assumeTrue(Files.isDirectory(workshop), workshop + " is not there");
        compare(workshop);
    }

    // a log line for the record, not a check: timings vary with the machine
    @Test
    void timeOnTheGamesTileGeometry() throws IOException {
        Path file = folder("fasterloading.gameDir").resolve("media/tileGeometry.txt");
        assumeTrue(Files.isRegularFile(file), file + " is not there");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            Comments.strip(text);
        }
        long game = Long.MAX_VALUE;
        long hooked = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            ScriptParser.stripComments(text);
            long t1 = System.nanoTime();
            // the hooked call: ours, then the game's method on its result
            ScriptParser.stripComments(Comments.strip(text));
            long t2 = System.nanoTime();
            game = Math.min(game, t1 - t0);
            hooked = Math.min(hooked, t2 - t1);
        }
        System.out.printf(Locale.ROOT, "%s (%d chars): the game's method %.1f ms, hooked %.2f ms (best of 3)%n",
                file, text.length(), game / 1e6, hooked / 1e6);
    }

    private static Path folder(String property) {
        String value = System.getProperty(property);
        assumeTrue(value != null, property + " is not set (the build sets it)");
        return Path.of(value);
    }

    private static int compare(Path root) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).filter(RealFilesTest::stripped).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long chars = 0;
        int withComments = 0;
        long t0 = System.nanoTime();
        for (Path file : files) {
            String text;
            try {
                // undecodable bytes become U+FFFD, the same input for both
                text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String game = ScriptParser.stripComments(text);
            String ours = Comments.strip(text);
            if (!game.equals(ours)) {
                assertEquals(game, ours, "for " + file);
            }
            // what the hook hands to the game's method, which must return it unchanged
            String again = ScriptParser.stripComments(ours);
            if (!again.equals(ours)) {
                assertEquals(ours, again, "the game's method changes the result for " + file);
            }
            chars += text.length();
            if (!game.equals(text)) {
                withComments++;
            }
        }
        System.out.printf(Locale.ROOT, "%s: %d files (%d with comments, %.1f M chars) the same and unchanged by the"
                + " game's method, in %.1f s%n", root, files.size(), withComments, chars / 1e6,
                (System.nanoTime() - t0) / 1e9);
        return files.size();
    }

    private static boolean stripped(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (NAMES.contains(name)) {
            return true;
        }
        String path = file.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return path.contains("/media/scripts/") && name.endsWith(".txt");
    }
}
