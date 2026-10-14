package com.pzlauncher.zabric.processor;

import com.pzlauncher.zabric.processor.glue.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderPatchTest {
    private static final String PATCH = """
            package p;

            import me.zed_0xff.zombie_buddy.Patch;

            %s@Patch(className = "zombie.Foo", methodName = "bar")
            public class Legacy {
                @Patch.OnEnter
                public static void enter() {
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void patchWithoutMarkerIsAnError() {
        Compiler.Result result = Compiler.compile(dir, Map.of(), "p/Legacy.java", PATCH.formatted(""));
        assertFalse(result.success());
        assertEquals(List.of(Messages.loaderPatch("p.Legacy")), result.errors());
        String message = result.errors().get(0);
        assertTrue(message.contains("hook through Zabric's API"));
        assertTrue(message.contains("@UnsafeLoaderPatch"));
        assertTrue(message.contains("not recommended"));
        assertTrue(message.contains("no Zabric gate, error catching or target check"));
    }

    @Test
    void markedPatchCompilesWithAWarningAndNothingGenerated() throws IOException {
        Compiler.Result result = Compiler.compile(dir, Map.of(), "p/Legacy.java",
                PATCH.formatted("@com.pzlauncher.zabric.hook.UnsafeLoaderPatch\n"));
        assertEquals(List.of(), result.errors());
        assertTrue(result.success());
        assertEquals(List.of(Messages.unsafeLoaderPatch("p.Legacy")), result.warnings());
        String message = result.warnings().get(0);
        assertTrue(message.contains("p.Legacy"));
        assertTrue(message.contains("Not recommended"));
        assertTrue(message.contains("Zabric takes no responsibility when it breaks"));
        assertFalse(result.generatedAnything());
    }

    @Test
    void nestedPatchClassIsFound() {
        Compiler.Result result = Compiler.compile(dir, Map.of(), "p/Outer.java", """
                package p;

                public class Outer {
                    @me.zed_0xff.zombie_buddy.Patch(className = "zombie.Foo", methodName = "bar")
                    public static class Inner {
                    }
                }
                """);
        assertEquals(List.of(Messages.loaderPatch("p.Outer.Inner")), result.errors());
    }

    @Test
    void markerWithoutPatchIsAnError() {
        Compiler.Result result = Compiler.compile(dir, Map.of(), "p/Plain.java", """
                package p;

                @com.pzlauncher.zabric.hook.UnsafeLoaderPatch
                public class Plain {
                }
                """);
        assertEquals(List.of(Messages.markerWithoutPatch("p.Plain")), result.errors());
    }
}
