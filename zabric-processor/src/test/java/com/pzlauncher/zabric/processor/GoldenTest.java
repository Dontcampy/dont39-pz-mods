package com.pzlauncher.zabric.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The output for the weave test's hook classes must equal the glue and listings that test weaves. */
class GoldenTest {
    private static final Path WEAVE = Path.of(System.getProperty("zabric.weaveSources"));
    private static final String PACKAGE = "com/pzlauncher/zabric/hook/weave/";
    private static final List<String> HOOK_CLASSES = List.of("AlwaysHooks", "DefaultHooks", "GatedHooks",
            "ItemHooks", "NestedHooks", "ShapeHooks");

    @Test
    void weaveHooksGenerateTheReferenceGlue(@TempDir Path dir) throws IOException {
        List<Path> sources = Stream.concat(HOOK_CLASSES.stream(),
                        Stream.of("Target", "Shapes", "Nested", "TestGate", "Events"))
                .map(name -> WEAVE.resolve("java/" + PACKAGE + name + ".java")).toList();
        Compiler.Result result = Compiler.compile(dir,
                Map.of("zabric.modId", "weave", "zabric.javaPkgName", "com.pzlauncher.zabric.hook.weave.glue"),
                sources);
        assertEquals(List.of(), result.errors());
        assertEquals(List.of(), result.warnings());
        assertTrue(result.success());

        String reference = read(WEAVE.resolve("java/" + PACKAGE + "glue/WeaveGlue.java"));
        // the reference starts with a header comment
        String expected = reference.substring(reference.indexOf("\npackage ") + 1);
        assertEquals(expected, result.source(PACKAGE + "glue/WeaveGlue.java"));

        for (String hookClass : HOOK_CLASSES) {
            String listing = "META-INF/zabric/hooks/com.pzlauncher.zabric.hook.weave." + hookClass + ".txt";
            assertEquals(read(WEAVE.resolve("resources/" + listing)),
                    result.resource(listing), listing);
        }
    }

    // a Windows checkout with core.autocrlf has CRLF; the generator writes LF
    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
