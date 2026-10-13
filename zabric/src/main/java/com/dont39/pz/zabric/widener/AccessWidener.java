package com.dont39.pz.zabric.widener;

import com.dont39.pz.zabric.classtweaker.ClassTweaker;
import com.dont39.pz.zabric.classtweaker.ClassTweaker.Access;
import com.dont39.pz.zabric.classtweaker.ClassTweaker.Entry;
import com.dont39.pz.zabric.loader.Loader;
import com.dont39.pz.zabric.restart.RestartList;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.ModifierAdjustment;
import net.bytebuddy.description.modifier.ModifierContributor;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.utility.JavaModule;
import org.jspecify.annotations.NonNull;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;

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
    private static final String LIST_SUFFIX = ".classtweaker";
    private static final String PREFIX = "[Zabric] ";
    private static final String NOT_PRELOADED = "Zabric did not run at game start: it registers itself as a "
            + Loader.name() + " preload mod on its first launch; restart the game once";

    /** One mod's list as found in one jar; {@code problem} is set when it was not applied. */
    private record Listed(String modId, String source, List<Entry> entries, String problem) {
    }

    /**
     * What premain found; {@code lists} by mod id, then by the list's sha256; {@code unreadable}: the error for each
     * folder or jar it could not read.
     */
    private record Scan(List<Path> roots, Map<String, Map<String, Listed>> lists, Map<Path, String> unreadable,
                        int directives) {
    }

    private record Outcome(String why, Throwable error, int directives) {
    }

    private static volatile Scan scan;
    private static volatile String failure;
    private static volatile Throwable failureError;
    // a class fails when it loads, after premain
    private static final Map<String, Throwable> failedTypes = new ConcurrentHashMap<>();

    // FieldManifestation.PLAIN would also drop volatile; the build only drops final
    private static final ModifierContributor.ForField NON_FINAL = new ModifierContributor.ForField() {
        @Override
        public int getMask() {
            return EMPTY_MASK;
        }

        @Override
        public int getRange() {
            return Modifier.FINAL;
        }

        @Override
        public boolean isDefault() {
            return false;
        }
    };

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
        List<ModFolders.Root> roots = ModFolders.roots();
        Map<String, Map<String, Listed>> lists = new LinkedHashMap<>();
        Map<Path, String> unreadable = new LinkedHashMap<>();
        for (ModFolders.Root root : roots) {
            List<Path> mods;
            try {
                mods = root.modFolders();
            } catch (IOException | RuntimeException e) {
                unreadable.put(real(root.dir()), e.toString());
                continue;
            }
            for (Path mod : mods) {
                try {
                    for (Path jar : ModFolders.jars(mod)) {
                        readLists(jar, lists, unreadable);
                    }
                } catch (IOException | RuntimeException e) {
                    unreadable.put(real(mod), e.toString());
                }
            }
        }

        // ModifierAdjustment applies only the first matching adjustment, so each member gets all of its modifiers in one
        Map<String, ModifierAdjustment> adjustments = new LinkedHashMap<>();
        Map<String, Map<String, Set<ModifierContributor.ForField>>> fields = new LinkedHashMap<>();
        Set<String> directives = new LinkedHashSet<>();
        lists.values().forEach(byHash -> byHash.values().forEach(listed -> listed.entries().forEach(e -> {
            if (!directives.add(e.directive())) {
                return;
            }
            String type = type(e);
            ModifierAdjustment a = adjustments.getOrDefault(type, new ModifierAdjustment());
            switch (e.kind()) {
                case CLASS -> a = a.withTypeModifiers(Visibility.PUBLIC);
                case METHOD -> a = a.withConstructorModifiers(isConstructor().and(hasDescriptor(e.descriptor())),
                        Visibility.PUBLIC);
                case FIELD -> fields.computeIfAbsent(type, k -> new LinkedHashMap<>())
                        .computeIfAbsent(e.name() + " " + e.descriptor(), k -> new LinkedHashSet<>())
                        .add(e.access() == Access.MUTABLE ? NON_FINAL : Visibility.PUBLIC);
            }
            adjustments.put(type, a);
        })));
        fields.forEach((type, byField) -> byField.forEach((field, modifiers) -> {
            String[] nameAndDescriptor = field.split(" ");
            adjustments.put(type, adjustments.get(type).withFieldModifiers(
                    named(nameAndDescriptor[0]).and(hasDescriptor(nameAndDescriptor[1])), new ArrayList<>(modifiers)));
        }));

        Set<String> loaded = new TreeSet<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (adjustments.remove(c.getName()) != null) {
                loaded.add(c.getName());
            }
        }
        if (!loaded.isEmpty()) {
            lists.values().forEach(byHash -> byHash.replaceAll((hash, listed) -> {
                Set<String> named = new TreeSet<>();
                listed.entries().forEach(e -> {
                    if (loaded.contains(type(e))) {
                        named.add(type(e));
                    }
                });
                return listed.problem() != null || named.isEmpty() ? listed : new Listed(listed.modId(),
                        listed.source(), listed.entries(), "already loaded before Zabric ran, so they keep their"
                        + " access: " + String.join(", ", named));
            }));
        }

        if (!adjustments.isEmpty()) {
            // not retransforming, so ZombieBuddy's later retransformations start from the widened bytes
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.DISABLED)
                    .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                    .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override
                        public void onError(@NonNull String typeName, ClassLoader cl, JavaModule module,
                                            boolean isLoaded, @NonNull Throwable t) {
                            failedTypes.put(typeName, t);
                        }
                    })
                    .type(namedOneOf(adjustments.keySet().toArray(new String[0])))
                    .transform((builder, type, cl, module, domain) ->
                            builder.visit(adjustments.get(type.getName())))
                    .installOn(inst);
        }
        List<Path> scanned = roots.stream().map(r -> real(r.dir())).toList();
        return new Scan(scanned, lists, unreadable, directives.size());
    }

    private static void readLists(Path jar, Map<String, Map<String, Listed>> lists, Map<Path, String> unreadable) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                String file = name.substring(Math.min(name.length(), LIST_DIR.length()));
                if (!name.startsWith(LIST_DIR) || !file.endsWith(LIST_SUFFIX) || file.contains("/")) {
                    continue;
                }
                String modId = file.substring(0, file.length() - LIST_SUFFIX.length());
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                String source = jar + "!/" + name;
                Listed listed;
                try {
                    listed = new Listed(modId, source, ClassTweaker.parse(source, lines(bytes)), null);
                } catch (IllegalArgumentException e) {
                    listed = new Listed(modId, source, List.of(), "cannot read the list: " + e.getMessage());
                }
                lists.computeIfAbsent(modId, k -> new LinkedHashMap<>()).putIfAbsent(sha256(bytes), listed);
            }
        } catch (IOException | RuntimeException e) {
            unreadable.put(real(jar), e.toString());
        }
    }

    /**
     * Why the list {@code modId} ships in {@code host}'s jar is not in effect, or null when it is. Reads the jar
     * {@code host} was loaded from, not a class loader resource: all mod jars share one class loader, so that could
     * return another mod's or another copy's list.
     */
    public static String problem(String modId, Class<?> host) {
        return check(modId, host).why();
    }

    /** Logs {@link #problem} once the game's log is up, as {@code [<modId>] access widener: ...}, and returns it. */
    public static String report(String modId, Class<?> host) {
        Outcome outcome = check(modId, host);
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
        s.lists().values().forEach(byHash -> byHash.values().forEach(listed -> {
            if (listed.problem() != null) {
                problems.add(listed.modId() + " (" + listed.source() + "): " + listed.problem());
            }
        }));
        s.unreadable().forEach((path, why) -> problems.add("cannot read " + path + ": " + why));
        failedTypes.forEach((type, t) -> problems.add("widening " + type + " failed: " + t));
        if (problems.isEmpty()) {
            DebugType.Mod.println(PREFIX + "access widener: ok (%d mods, %d directives)", s.lists().size(),
                    s.directives());
        } else {
            DebugType.Mod.warn(PREFIX + "access widener: %d mods (%d directives); problems: %s", s.lists().size(),
                    s.directives(), String.join("; ", problems));
        }
    }

    private static Outcome check(String modId, Class<?> host) {
        Scan s = scan;
        if (s == null) {
            return new Outcome(failure == null ? NOT_PRELOADED : failure, failureError, 0);
        }
        Path jar;
        byte[] bytes;
        String name = LIST_DIR + modId + LIST_SUFFIX;
        try {
            jar = jarOf(host);
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) {
                    return new Outcome(jar + " has no " + name, null, 0);
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
            }
        } catch (IOException | URISyntaxException | RuntimeException e) {
            return new Outcome("cannot read " + name + " from the jar of " + host.getName() + ": " + e, e, 0);
        }

        Listed listed = s.lists().getOrDefault(modId, Map.of()).get(sha256(bytes));
        if (listed == null) {
            Path at = real(jar);
            for (Map.Entry<Path, String> u : s.unreadable().entrySet()) {
                if (at.startsWith(u.getKey())) {
                    return new Outcome("Zabric could not read " + u.getKey() + " at game start (" + u.getValue()
                            + "), so the list in " + jar + " was not applied", null, 0);
                }
            }
            if (s.roots().stream().anyMatch(at::startsWith)) {
                RestartList.add(modId);
                return new Outcome("the list in " + jar + " was not there or was different when the game started"
                        + " (the mod was installed or updated while the game ran); restart the game once", null, 0);
            }
            return new Outcome(jar + " is outside the folders Zabric reads at game start (" + s.roots()
                    + "), so its list was not applied", null, 0);
        }
        if (listed.problem() != null) {
            return new Outcome(listed.problem(), null, 0);
        }
        for (Entry e : listed.entries()) {
            Throwable t = failedTypes.get(type(e));
            if (t != null) {
                return new Outcome("widening " + type(e) + " failed: " + t, t, 0);
            }
        }
        String mismatch = verify(listed.entries(), host.getClassLoader());
        return new Outcome(mismatch, null, listed.entries().size());
    }

    // checks what compiled code does: access through the public lookup
    private static String verify(List<Entry> entries, ClassLoader loader) {
        List<String> wrong = new ArrayList<>();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        for (Entry e : entries) {
            Class<?> owner;
            try {
                owner = Class.forName(type(e), false, loader);
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

    private static Path jarOf(Class<?> host) throws URISyntaxException {
        CodeSource source = host.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException(host.getName() + " has no code source");
        }
        return Path.of(source.getLocation().toURI());
    }

    private static String type(Entry e) {
        return e.owner().replace('/', '.');
    }

    private static List<String> lines(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).lines().toList();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JRE has SHA-256
        }
    }

    // compare as the file system does (case, links); a folder that does not exist yet stays as it is
    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
