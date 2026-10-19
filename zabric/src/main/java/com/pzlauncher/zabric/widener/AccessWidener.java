package com.pzlauncher.zabric.widener;

import com.pzlauncher.zabric.classtweaker.ClassTweaker.Access;
import com.pzlauncher.zabric.classtweaker.ClassTweaker.Entry;
import com.pzlauncher.zabric.internal.Jars;
import com.pzlauncher.zabric.loader.Loader;
import com.pzlauncher.zabric.restart.RestartList;
import com.pzlauncher.zabric.widener.WidenerScan.Found;
import com.pzlauncher.zabric.widener.WidenerScan.Listed;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

/**
 * Makes the classes, fields and constructors named in the {@code .classtweaker} lists of all installed mods public as
 * they load, so code compiled against widened jars can use them directly. Modifiers can only change when a class is
 * defined, hence a preload mod, and hence every installed mod: at premain the game has not chosen the enabled ones.
 * <p>
 * A mod ships its list as {@link #LIST_DIR}{@code <modId>.classtweaker} in its jar and asks {@link #problem} whether it
 * is in effect.
 */
public final class AccessWidener {
    public static final String LIST_DIR = "META-INF/zabric/";
    private static final String NOT_PRELOADED = "Zabric did not run at game start: it registers itself as a "
            + Loader.name() + " preload mod on its first launch; restart the game once";

    record Scan(Found found, int directives) {
    }

    /** {@code restart}: the list appeared or changed while the game ran, so a restart puts it in effect. */
    record Outcome(String why, Throwable error, int directives, boolean restart) {
    }

    private static volatile Scan scan;
    private static volatile String failure;
    private static volatile Throwable failureError;

    private AccessWidener() {
    }

    /**
     * Called by Zabric's PreMain. Never throws, logs nothing and touches no game class: the game's log is not up and the
     * listed classes must load after the transformer is in place.
     */
    public static void install() {
        try {
            scan = apply();
        } catch (Throwable t) {
            failureError = t;
            failure = "unexpected error at game start: " + t;
        }
    }

    private static Scan apply() {
        Instrumentation inst;
        try {
            inst = Loader.instrumentation();
        } catch (IllegalStateException e) {
            failureError = e;
            failure = e.getMessage();
            return null;
        }
        Found found = WidenerScan.run();
        return new Scan(found, WidenerTransform.install(inst, found.lists()));
    }

    /**
     * Why the list {@code modId} ships in {@code host}'s jar is not in effect, or null when it is. Reads the jar
     * {@code host} was loaded from, not a class loader resource: all mod jars share one class loader, so that could
     * return another mod's or another copy's list.
     */
    public static String problem(String modId, Class<?> host) {
        Outcome outcome = check(modId, host);
        if (outcome.restart()) {
            RestartList.add(modId);
        }
        return outcome.why();
    }

    /** Logs {@link #problem} once the game's log is up, as {@code [<modId>] access widener: ...}, and returns it. */
    public static String report(String modId, Class<?> host) {
        Outcome outcome = check(modId, host);
        if (outcome.restart()) {
            RestartList.add(modId);
        }
        if (outcome.why() == null) {
            DebugType.Mod.println("[%s] access widener: ok (%d directives)", modId, outcome.directives());
        } else if (outcome.error() != null) {
            // the game's varargs printException drops its arguments
            DebugType.Mod.printException(outcome.error(), "[" + modId + "] access widener: not applied: "
                    + outcome.why(), LogSeverity.Error);
        } else {
            DebugType.Mod.warn("[%s] access widener: not applied: %s", modId, outcome.why());
        }
        return outcome.why();
    }

    /** Logs what the game-start widening found across all installed mods, as one line. */
    public static void report() {
        Scan s = scan;
        if (s == null) {
            String why = failure == null ? NOT_PRELOADED : failure;
            if (failureError != null) {
                DebugType.Mod.printException(failureError, PREFIX + "access widener: not applied: " + why,
                        LogSeverity.Error);
            } else {
                DebugType.Mod.warn(PREFIX + "access widener: not applied: %s", why);
            }
            return;
        }
        List<String> problems = new ArrayList<>();
        s.found().lists().values().forEach(byHash -> byHash.values().forEach(listed -> {
            if (listed.problem() != null) {
                problems.add(listed.modId() + " (" + listed.source() + "): " + listed.problem());
            }
        }));
        s.found().unreadable().forEach((path, why) -> problems.add("cannot read " + path + ": " + why));
        WidenerTransform.failedTypes().forEach((type, t) -> problems.add("widening " + type + " failed: " + t));
        int mods = s.found().lists().size();
        if (problems.isEmpty()) {
            DebugType.Mod.println(PREFIX + "access widener: ok (%d mods, %d directives)", mods, s.directives());
        } else {
            DebugType.Mod.warn(PREFIX + "access widener: %d mods (%d directives); problems: %s", mods, s.directives(),
                    String.join("; ", problems));
        }
    }

    private static Outcome check(String modId, Class<?> host) {
        Scan s = scan;
        if (s == null) {
            return new Outcome(failure == null ? NOT_PRELOADED : failure, failureError, 0, false);
        }
        return check(s, modId, host);
    }

    static Outcome check(Scan s, String modId, Class<?> host) {
        Path jar;
        byte[] bytes;
        String name = LIST_DIR + modId + WidenerScan.LIST_SUFFIX;
        try {
            jar = Jars.codeSource(host);
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) {
                    return new Outcome(jar + " has no " + name, null, 0, false);
                }
                bytes = Jars.bytes(zip, entry);
            }
        } catch (IOException | URISyntaxException | RuntimeException e) {
            return new Outcome("cannot read " + name + " from the jar of " + host.getName() + ": " + e, e, 0, false);
        }

        Found found = s.found();
        Listed listed = found.lists().getOrDefault(modId, Map.of()).get(WidenerScan.sha256(bytes));
        if (listed == null) {
            Path at = WidenerScan.real(jar);
            for (Map.Entry<Path, String> u : found.unreadable().entrySet()) {
                if (at.startsWith(u.getKey())) {
                    return new Outcome("Zabric could not read " + u.getKey() + " at game start (" + u.getValue()
                            + "), so the list in " + jar + " was not applied", null, 0, false);
                }
            }
            if (found.roots().stream().anyMatch(at::startsWith)) {
                return new Outcome("the list in " + jar + " was not there or was different when the game started"
                        + " (the mod was installed or updated while the game ran); restart the game once", null, 0,
                        true);
            }
            return new Outcome(jar + " is outside the folders Zabric reads at game start (" + found.roots()
                    + "), so its list was not applied", null, 0, false);
        }
        if (listed.problem() != null) {
            return new Outcome(listed.problem(), null, 0, false);
        }
        for (Entry e : listed.entries()) {
            Throwable t = WidenerTransform.failedTypes().get(WidenerTransform.type(e));
            if (t != null) {
                return new Outcome("widening " + WidenerTransform.type(e) + " failed: " + t, t, 0, false);
            }
        }
        String mismatch = verify(listed.entries(), host.getClassLoader());
        return new Outcome(mismatch, null, listed.entries().size(), false);
    }

    // checks what compiled code does: access through the public lookup
    private static String verify(List<Entry> entries, ClassLoader loader) {
        List<String> wrong = new ArrayList<>();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        for (Entry e : entries) {
            Class<?> owner;
            try {
                owner = Class.forName(WidenerTransform.type(e), false, loader);
            } catch (ClassNotFoundException ex) {
                continue; // a target mod that is not enabled
            }
            try {
                switch (e.kind()) {
                    case CLASS -> lookup.accessClass(owner);
                    case METHOD -> lookup.findConstructor(owner,
                            MethodType.fromMethodDescriptorString(e.descriptor(), loader));
                    case FIELD -> {
                        Field f = owner.getDeclaredField(e.name());
                        boolean isStatic = Modifier.isStatic(f.getModifiers());
                        if (e.access() == Access.MUTABLE) {
                            if (Modifier.isFinal(f.getModifiers())) {
                                throw new IllegalAccessException("still final");
                            }
                        } else if (isStatic) {
                            lookup.findStaticGetter(owner, e.name(), f.getType());
                        } else {
                            lookup.findGetter(owner, e.name(), f.getType());
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                wrong.add(e.directive() + " (" + ex + ")");
            }
        }
        return wrong.isEmpty() ? null : "these are not in effect: " + String.join("; ", wrong);
    }
}
