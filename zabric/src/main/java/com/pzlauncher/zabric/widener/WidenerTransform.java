package com.pzlauncher.zabric.widener;

import com.pzlauncher.zabric.classtweaker.ClassTweaker.Access;
import com.pzlauncher.zabric.classtweaker.ClassTweaker.Entry;
import com.pzlauncher.zabric.widener.WidenerScan.Listed;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.ModifierAdjustment;
import net.bytebuddy.description.modifier.ModifierContributor;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.utility.JavaModule;
import org.jspecify.annotations.NonNull;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;

/** Changes the modifiers the lists name as their classes load. */
final class WidenerTransform {
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

    private WidenerTransform() {
    }

    /**
     * Installs the transformer for every list's directives and returns how many distinct directives there are. A list
     * that names classes already loaded gets that as its problem.
     */
    static int install(Instrumentation inst, Map<String, Map<String, Listed>> lists) {
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
        return directives.size();
    }

    /** The classes whose widening failed as they loaded, with the error. */
    static Map<String, Throwable> failedTypes() {
        return failedTypes;
    }

    static String type(Entry e) {
        return e.owner().replace('/', '.');
    }
}
