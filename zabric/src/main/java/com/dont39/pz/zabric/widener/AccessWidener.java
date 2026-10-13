package com.dont39.pz.zabric.widener;

import com.dont39.pz.zabric.classtweaker.ClassTweaker;
import com.dont39.pz.zabric.classtweaker.ClassTweaker.Access;
import com.dont39.pz.zabric.classtweaker.ClassTweaker.Entry;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.ModifierAdjustment;
import net.bytebuddy.description.modifier.ModifierContributor;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.utility.JavaModule;
import org.jspecify.annotations.NonNull;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;

/**
 * Makes the classes, fields and constructors in the features' merged {@code .classtweaker} list public as they load, so
 * feature code compiled against widened jars can use them directly. Modifiers can only change when a class is defined,
 * hence this preload mod.
 */
public final class AccessWidener {
    private static volatile String problem = "it did not run at game start: Zabric registers itself"
            + " as a ZombieBuddy preload mod on its first launch; restart the game once";
    private static volatile Throwable error;
    private static volatile List<Entry> entries = List.of();
    private static volatile ClassLoader loader;

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

    /** Why the listed members are not public, or null when they are. */
    public static String problem() {
        return problem;
    }

    /**
     * Installs the widening for the directives in {@code resource} of {@code host}'s jar. Never throws: ZombieBuddy
     * calls it, and the outcome is reported later through {@link #report}.
     */
    public static void install(Class<?> host, String resource) {
        try {
            problem = apply(host, resource);
        } catch (Throwable t) {
            error = t;
            problem = "unexpected error while installing it: " + t;
        }
    }

    private static String apply(Class<?> host, String resource) throws Exception {
        Instrumentation inst = instrumentation();
        if (inst == null) {
            return "ZombieBuddy " + zombieBuddyVersion() + " does not expose its Instrumentation"
                    + " (me.zed_0xff.zombie_buddy.Loader.g_instrumentation)";
        }
        loader = host.getClassLoader();
        List<Entry> listed;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return resource + " is missing from the mod jar";
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            listed = ClassTweaker.parse(resource, reader.lines().collect(Collectors.toList()));
        } catch (IllegalArgumentException e) {
            return "cannot read the list: " + e.getMessage();
        }

        // ModifierAdjustment applies only the first matching adjustment, so each member gets all of its modifiers in one
        Map<String, Map<String, List<ModifierContributor.ForField>>> fields = new LinkedHashMap<>();
        Map<String, ModifierAdjustment> adjustments = new LinkedHashMap<>();
        for (Entry e : listed) {
            String type = e.owner().replace('/', '.');
            ModifierAdjustment a = adjustments.getOrDefault(type, new ModifierAdjustment());
            switch (e.kind()) {
                case CLASS -> a = a.withTypeModifiers(Visibility.PUBLIC);
                case METHOD -> a = a.withConstructorModifiers(isConstructor().and(hasDescriptor(e.descriptor())),
                        Visibility.PUBLIC);
                case FIELD -> fields.computeIfAbsent(type, k -> new LinkedHashMap<>())
                        .computeIfAbsent(e.name() + " " + e.descriptor(), k -> new ArrayList<>())
                        .add(e.access() == Access.MUTABLE ? NON_FINAL : Visibility.PUBLIC);
            }
            adjustments.put(type, a);
        }
        fields.forEach((type, byField) -> byField.forEach((field, modifiers) -> {
            String[] nameAndDescriptor = field.split(" ");
            adjustments.put(type, adjustments.get(type).withFieldModifiers(
                    named(nameAndDescriptor[0]).and(hasDescriptor(nameAndDescriptor[1])), modifiers));
        }));
        TreeSet<String> loaded = new TreeSet<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (adjustments.containsKey(c.getName())) {
                loaded.add(c.getName());
            }
        }
        if (!loaded.isEmpty()) {
            return "already loaded before it could run, so they keep their access: " + String.join(", ", loaded);
        }

        // not retransforming, so ZombieBuddy's later retransformations start from the widened bytes
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.DISABLED)
                .with(AgentBuilder.TypeStrategy.Default.DECORATE)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(@NonNull String typeName, ClassLoader cl, JavaModule module, boolean isLoaded,
                                        @NonNull Throwable t) {
                        error = t;
                        problem = "widening " + typeName + " failed: " + t;
                    }
                })
                .type(namedOneOf(adjustments.keySet().toArray(new String[0])))
                .transform((builder, type, cl, module, domain) ->
                        builder.visit(adjustments.get(type.getName())))
                .installOn(inst);
        entries = listed;
        return null;
    }

    /**
     * Logs the outcome once the game's log is up, checking that the listed members of the classes present are public.
     * Returns {@link #problem()}.
     */
    public static String report(String modName) {
        String prefix = "[" + modName + "] access widener: ";
        if (problem == null) {
            String mismatch = verify();
            if (mismatch != null) {
                problem = mismatch;
            }
        }
        String why = problem;
        if (why == null) {
            DebugType.Mod.println(prefix + "ok (" + entries.size() + " directives)");
        } else if (error != null) {
            DebugType.Mod.printException(error, prefix + "not applied: " + why, LogSeverity.Error);
        } else {
            DebugType.Mod.warn(prefix + "not applied: " + why);
        }
        return why;
    }

    // checks what compiled code does: access through the public lookup
    private static String verify() {
        List<String> wrong = new ArrayList<>();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        for (Entry e : entries) {
            Class<?> owner;
            try {
                owner = Class.forName(e.owner().replace('/', '.'), false, loader);
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

    // ZombieBuddy keeps it package-private; the game JRE has no jdk.attach to get another one
    private static Instrumentation instrumentation() {
        try {
            Field f = Class.forName("me.zed_0xff.zombie_buddy.Loader").getDeclaredField("g_instrumentation");
            f.setAccessible(true);
            return (Instrumentation) f.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            error = e;
            return null;
        }
    }

    private static String zombieBuddyVersion() {
        try {
            return me.zed_0xff.zombie_buddy.ZombieBuddy.getVersion();
        } catch (RuntimeException | LinkageError e) {
            return "(unknown version)";
        }
    }
}
