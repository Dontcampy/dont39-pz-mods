package com.pzlauncher.zabric.hook.weave;

import com.pzlauncher.zabric.hook.impl.HookListing;
import me.zed_0xff.zombie_buddy.PatchEngine;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** What ZombieBuddy has at game start, for the tests: its Instrumentation and the glue packages it wove. */
public final class Weaving {
    public static final String GLUE_PACKAGE = "com.pzlauncher.zabric.hook.weave.glue";

    private static Instrumentation instrumentation;
    private static final Set<String> woven = new HashSet<>();

    private Weaving() {
    }

    /** The Instrumentation ZombieBuddy and Zabric use, attached to the test JVM. */
    public static synchronized Instrumentation instrumentation() {
        if (instrumentation == null) {
            instrumentation = ByteBuddyAgent.install();
            try {
                // ZombieBuddy's agent sets it at game start; package-private, so set it the way Zabric's Loader reads it
                Field f = Class.forName("me.zed_0xff.zombie_buddy.Loader").getDeclaredField("g_instrumentation");
                f.setAccessible(true);
                f.set(null, instrumentation);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return instrumentation;
    }

    /** Weaves the patch classes of {@code glue} once per JVM: weaving twice would nest the advice twice. */
    public static synchronized void weave(String glue) {
        instrumentation();
        if (woven.add(glue)) {
            PatchEngine.applyPatches(glue, Weaving.class.getClassLoader());
        }
    }

    /** The reference glue, woven. */
    public static void weaveReference() {
        weave(GLUE_PACKAGE);
    }

    /** Where the reference listings are: Gradle keeps test resources apart from test classes. */
    public static Path listings() {
        String name = HookListing.DIR + DefaultHooks.class.getName() + HookListing.SUFFIX;
        URL url = Weaving.class.getClassLoader().getResource(name);
        try {
            Path root = Path.of(url.toURI());
            for (int i = 0; i < name.split("/").length; i++) {
                root = root.getParent();
            }
            return root;
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
