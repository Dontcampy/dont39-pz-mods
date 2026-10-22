package com.dont39.pz.fasterloading.luafiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link FolderWalk} against the game's {@code LuaManager.searchFolders} on the game's and the installed Workshop mods'
 * Lua folders, as the build finds them ({@code pzDir}; a folder that is not there is skipped), and on generated trees.
 */
class FolderWalkTest {
    private static final boolean WINDOWS = File.separatorChar == '\\';
    private static final List<String> SIDES = List.of("shared", "client", "server");

    @TempDir
    Path temp;

    @Test
    void theGamesFolders() throws IOException {
        Path lua = folder("fasterloading.gameDir").resolve("media/lua");
        assumeTrue(Files.isDirectory(lua), lua + " is not there");
        int files = 0;
        for (String side : SIDES) {
            files += compare(lua.getParent().getParent(), lua.resolve(side), true);
        }
        // the game ships about a thousand Lua files
        assertTrue(files > 500, "only " + files + " files under " + lua);
    }

    @Test
    void theWorkshopModsFolders() throws IOException {
        Path workshop = folder("fasterloading.workshop");
        assumeTrue(Files.isDirectory(workshop), workshop + " is not there");
        int roots = 0;
        int files = 0;
        long t0 = System.nanoTime();
        for (Path mod : children(workshop).stream().flatMap(item -> children(item.resolve("mods")).stream()).toList()) {
            // a mod's version folders (common, 42, 42.x) and, from Build 41, the mod folder itself
            List<Path> folders = new ArrayList<>(children(mod));
            folders.add(mod);
            for (Path version : folders) {
                for (String side : SIDES) {
                    Path lua = version.resolve("media/lua").resolve(side);
                    if (Files.isDirectory(lua)) {
                        roots++;
                        files += compare(version, lua, true);
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "%s: %d Lua folders, %d files the same in %.1f s%n", workshop, roots, files,
                (System.nanoTime() - t0) / 1e9);
        assertTrue(roots > 0, "no mod Lua folders under " + workshop);
    }

    @Test
    void aGeneratedTree() throws IOException {
        Path root = temp.resolve("Mod/media/lua/shared");
        file(root.resolve("a.lua"));
        file(root.resolve("B.LUA"));
        file(root.resolve("readme.txt"));
        file(root.resolve("Sub/Deeper/c.lua"));
        file(root.resolve("Sub/d.Lua"));
        file(root.resolve("x.lua.bak"));
        file(root.resolve("dir.lua/e.lua"));
        Files.createDirectories(root.resolve("Empty"));
        Files.createDirectories(root.resolve("Sub/Empty"));
        assertEquals(5, compare(temp.resolve("Mod"), root, true));
    }

    @Test
    void aRootThatIsAFileOrMissing() throws IOException {
        Path file = file(temp.resolve("Mod/media/lua/shared.lua"));
        assertEquals(1, compare(temp.resolve("Mod"), file, false));
        assertEquals(0, compare(temp.resolve("Mod"), temp.resolve("Mod/media/lua/client"), false));
        Files.createDirectories(temp.resolve("Mod/media/lua/server"));
        assertEquals(0, compare(temp.resolve("Mod"), temp.resolve("Mod/media/lua/server"), false));
    }

    @Test
    void aJunctionTakesTheGamesLookup() throws IOException, InterruptedException {
        assumeTrue(WINDOWS, "junctions are Windows'");
        Path outside = temp.resolve("Outside");
        file(outside.resolve("Inner/f.lua"));
        Path root = temp.resolve("Mod/media/lua/shared");
        file(root.resolve("a.lua"));
        // a junction to a folder outside the mod resolves there, and its files' paths with it
        int exit = new ProcessBuilder("cmd", "/c", "mklink", "/J", root.resolve("Linked").toString(),
                outside.toString()).inheritIO().start().waitFor();
        assumeTrue(exit == 0, "mklink /J failed");
        FolderWalk walk = walk(temp.resolve("Mod"), root);
        assertEquals(game(temp.resolve("Mod"), root), walk.files());
        assertEquals(2, walk.canonicalCalls(), "the root and the junction");
    }

    @Test
    void aSymbolicLinkTakesTheGamesLookup() throws IOException {
        Path outside = temp.resolve("Outside");
        file(outside.resolve("f.lua"));
        Path root = temp.resolve("Mod/media/lua/shared");
        file(root.resolve("a.lua"));
        try {
            Files.createSymbolicLink(root.resolve("Linked"), outside);
        } catch (FileSystemException | UnsupportedOperationException e) {
            assumeTrue(false, "no symbolic links here: " + e.getMessage());
        }
        FolderWalk walk = walk(temp.resolve("Mod"), root);
        assertEquals(game(temp.resolve("Mod"), root), walk.files());
        assertEquals(2, walk.canonicalCalls(), "the root and the link");
    }

    @Test
    void namesWindowsNormalizeTakeTheGamesLookup() throws IOException, InterruptedException {
        assumeTrue(WINDOWS, "a trailing dot or space is Windows' name normalization");
        Path root = temp.resolve("Mod/media/lua/shared");
        // each with its twin without the dot or space, which Windows opens in its place
        file(root.resolve("Dot/a.lua"));
        file(root.resolve("Space/b.lua"));
        List<String> odd = List.of("Dot.", "Space ", "Alone.");
        try {
            for (String name : odd) {
                int exit = raw("mkdir", root, name);
                assumeTrue(exit == 0, "mkdir " + name + " failed");
            }
            String[] names = root.toFile().list();
            assumeTrue(names != null && List.of(names).containsAll(odd),
                    "the odd names were not made: " + (names == null ? null : List.of(names)));
            FolderWalk walk = walk(temp.resolve("Mod"), root);
            assertEquals(game(temp.resolve("Mod"), root), walk.files());
            // the root and Dot., which Windows opens as Dot; java.io.File takes a trailing space for an invalid path,
            // and Alone. opens Alone, which is not there, so neither is a folder to the game
            assertEquals(2, walk.canonicalCalls());
        } finally {
            // the temp folder's cleanup cannot delete them either
            for (String name : odd) {
                raw("rmdir", root, name);
            }
        }
    }

    // only a \\?\ path keeps a trailing dot or space
    private static int raw(String command, Path folder, String name) throws IOException, InterruptedException {
        String path = "\\\\?\\" + folder.toAbsolutePath() + "\\" + name;
        return new ProcessBuilder("cmd", "/c", command, path).inheritIO().start().waitFor();
    }

    @Test
    void aFolderThatCannotBeListedThrows() {
        File unlistable = new File(temp.toFile(), "Unlistable") {
            @Override
            public boolean isDirectory() {
                return true;
            }

            @Override
            public String[] list() {
                return null;
            }
        };
        assertThrows(IOException.class, () -> FolderWalk.lua(uri(temp), unlistable,
                ZomboidFileSystem.instance::getRelativeFile));
    }

    // the files, after checking ours are the game's; plain: no links or odd names below the root, so one lookup
    private static int compare(Path modFolder, Path root, boolean plain) throws IOException {
        List<String> game = game(modFolder, root);
        FolderWalk walk = walk(modFolder, root);
        assertEquals(game, walk.files(), "for " + root);
        if (plain) {
            assertTrue(walk.canonicalCalls() <= 1, walk.canonicalCalls() + " canonical lookups under " + root);
        }
        return game.size();
    }

    private static FolderWalk walk(Path modFolder, Path root) throws IOException {
        return FolderWalk.walk(uri(modFolder), root.toFile(), ZomboidFileSystem.instance::getRelativeFile);
    }

    private static List<String> game(Path modFolder, Path root) throws IOException {
        List<String> list = new ArrayList<>();
        LuaManager.loadList = list;
        LuaManager.searchFolders(uri(modFolder), root.toFile());
        return list;
    }

    // as LoadDirBase makes a mod folder's
    private static URI uri(Path folder) throws IOException {
        return new File(folder.toFile().getCanonicalFile().getPath().toLowerCase(Locale.ENGLISH)).toURI();
    }

    private static Path file(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "-- " + file.getFileName());
    }

    private static List<Path> children(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(folder)) {
            return list.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Path folder(String property) {
        String value = System.getProperty(property);
        assumeTrue(value != null, property + " is not set (the build sets it)");
        return Path.of(value);
    }
}
