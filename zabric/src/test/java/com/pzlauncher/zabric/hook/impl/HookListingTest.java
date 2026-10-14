package com.pzlauncher.zabric.hook.impl;

import com.pzlauncher.zabric.hook.ZabricHooks;
import com.pzlauncher.zabric.hook.weave.AlwaysHooks;
import com.pzlauncher.zabric.hook.weave.DefaultHooks;
import com.pzlauncher.zabric.hook.weave.GatedHooks;
import com.pzlauncher.zabric.hook.weave.ShapeHooks;
import com.pzlauncher.zabric.hook.weave.Target;
import com.pzlauncher.zabric.hook.weave.TestGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The listing format, and the target check on it. */
class HookListingTest {
    @Test
    void theReferenceListingsMatchTheirHookClasses() throws URISyntaxException {
        // Gradle keeps test resources apart from test classes; in a mod jar the listing sits next to the class
        for (Class<?> hooks : List.of(AlwaysHooks.class, DefaultHooks.class, GatedHooks.class,
                ShapeHooks.class)) {
            String name = HookListing.DIR + hooks.getName() + HookListing.SUFFIX;
            URL url = HookListingTest.class.getClassLoader().getResource(name);
            Path root = Path.of(url.toURI());
            for (int i = 0; i < name.split("/").length; i++) {
                root = root.getParent();
            }
            assertNull(HookListing.problem(hooks, root), hooks.getName());
            assertNull(HookListing.gateProblem(TestGate.class, root));
        }
    }

    @Test
    void aGateChecksTheHookClassesThatNameIt(@TempDir Path dir) throws IOException {
        String glue = "com.pzlauncher.zabric.hook.weave.glue.WeaveGlue$";
        Path jar = Listings.jar(dir, "gate.jar",
                "a.Matching", Listings.text("weave", TestGate.class.getName(), glue + "Target_ping",
                        Target.class.getName() + " ping ()V"),
                "a.Missing", Listings.text("weave", TestGate.class.getName(), glue + "Target_ping",
                        Target.class.getName() + " ping (I)V"),
                "a.OtherGate", Listings.text("weave", "a.Gate", "a.NoPatch", "a.NoClass run ()V"));
        assertEquals("the targets of a.Missing (" + jar + "!/" + HookListing.DIR + "a.Missing.txt) do not match: "
                + Target.class.getName() + " has no ping(I)V (it has ping()V)", HookListing.gateProblem(TestGate.class,
                jar));
        assertNull(HookListing.gateProblem(DefaultHooks.class, jar));
        assertNull(HookListing.gateProblem(TestGate.class, Listings.jar(dir, "none.jar")));
    }

    @Test
    void parsesEveryKindAndSkipsUnknownOnes() {
        HookListing listing = HookListing.parse("src", "a.Hooks", List.of(HookListing.HEADER, "", "mod m",
                "future kind", "gate a.Gate", "patch a.P$X", "hook a.T run (I)V a.P$X", "hook a.T <init> ()V a.P$X"));
        assertEquals("m", listing.modId());
        assertEquals("a.Gate", listing.gate());
        assertFalse(listing.defaultGate());
        assertEquals(List.of("a.P$X"), listing.patches());
        assertEquals(List.of(new HookListing.Hook("a.T", "run", "(I)V", "a.P$X"),
                new HookListing.Hook("a.T", "<init>", "()V", "a.P$X")), listing.hooks());
        assertTrue(HookListing.parse("src", "a.Hooks", List.of(HookListing.HEADER, "mod m", "gate default",
                "patch p", "hook a b c p")).defaultGate());
    }

    @Test
    void rejectsMalformedListings() {
        assertMessage("src: the first line is not 'zabric-hooks v1'", "mod m");
        assertMessage("src:3: a second mod line", HookListing.HEADER, "mod m", "mod n");
        assertMessage("src:2: expected 5 fields separated by single spaces: hook a.T run (I)V", HookListing.HEADER,
                "hook a.T run (I)V");
        assertMessage("src: needs a mod line, a gate line, and at least one patch and one hook line",
                HookListing.HEADER, "mod m", "gate default", "patch p");
        assertMessage("src: the hook of a.T.run(I)V names q, which is not a patch line", HookListing.HEADER, "mod m",
                "gate default", "patch p", "hook a.T run (I)V q");
    }

    @Test
    void aClassWithoutListingIsAProblem() {
        String problem = ZabricHooks.problem(HookListingTest.class);
        assertTrue(problem.startsWith("cannot read the hook listing of " + HookListingTest.class.getName() + ": "),
                problem);
    }

    private static void assertMessage(String message, String... lines) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HookListing.parse("src", "a.Hooks", List.of(lines)));
        assertEquals(message, e.getMessage());
    }
}
