package com.pzlauncher.zabric.hook.impl;

import com.pzlauncher.zabric.hook.HookGate;
import com.pzlauncher.zabric.loader.Loader;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.utility.OpenedClassReader;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The loader drops a target's patches without an error when ByteBuddy or the JVM rejects them, so this read-only
 * transformer, last in the chain, looks for the {@code GETSTATIC <glue>.MOD} every inlined advice contains.
 */
final class WeaveCheck implements ClassFileTransformer {
    private static final String MOD_FIELD = "MOD";
    private static final String MOD_DESCRIPTOR = "Lcom/pzlauncher/zabric/hook/ModHooks;";

    private record Watched(ModState mod, HookListing listing, HookListing.Hook hook) {
        String key() {
            return mod.modId() + " " + listing.hookClass();
        }
    }

    private record Finding(Watched watched, Throwable cause) {
    }

    // findings wait for retransformClasses: the JVM's verdict comes after the transformers
    private static final class Retransform {
        final String internalName;
        final List<Finding> findings = new ArrayList<>();
        boolean seen;

        Retransform(String internalName) {
            this.internalName = internalName;
        }
    }

    // by internal class name; replaced whole, so a class load costs one lookup
    private volatile Map<String, List<Watched>> watched = Map.of();
    private volatile ClassLoader loader;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Retransform> retransforming = new ThreadLocal<>();

    void afterPass(Instrumentation inst, ClassLoader loader, Collection<ModState> live, Set<ModState> entering) {
        this.loader = loader;
        Map<String, List<Watched>> table = new HashMap<>();
        Set<String> enteringTargets = new HashSet<>();
        for (ModState mod : live) {
            for (HookListing listing : mod.listings()) {
                for (HookListing.Hook hook : listing.hooks()) {
                    Watched w = new Watched(mod, listing, hook);
                    if (reported.contains(w.key())) {
                        continue;
                    }
                    table.computeIfAbsent(internalName(hook.owner()), k -> new ArrayList<>()).add(w);
                    if (entering.contains(mod)) {
                        enteringTargets.add(hook.owner());
                    }
                }
            }
        }
        table.replaceAll((k, v) -> List.copyOf(v));
        watched = Map.copyOf(table);
        inst.removeTransformer(this);
        inst.addTransformer(this, true);
        if (enteringTargets.isEmpty()) {
            return;
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (enteringTargets.contains(c.getName())) {
                retransform(inst, c, entering);
            }
        }
    }

    void clear() {
        watched = Map.of();
    }

    private void retransform(Instrumentation inst, Class<?> target, Set<ModState> entering) {
        String internalName = internalName(target.getName());
        Retransform check = new Retransform(internalName);
        Throwable failed = null;
        retransforming.set(check);
        try {
            inst.retransformClasses(target);
        } catch (Throwable t) {
            failed = t;
        } finally {
            retransforming.remove();
        }
        if (failed == null && check.seen) {
            check.findings.forEach(f -> report(f.watched(), f.cause()));
            return;
        }
        // the class keeps its earlier bytes, without what this pass's mods added
        Throwable cause = failed != null ? failed : new IllegalStateException("the weave check did not see "
                + target.getName() + " while it was retransformed");
        for (Watched w : watched.getOrDefault(internalName, List.of())) {
            if (entering.contains(w.mod()) && w.mod().live()) {
                report(w, cause);
            }
        }
    }

    @Override
    public byte[] transform(ClassLoader classLoader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        List<Watched> hooks = watched.get(className);
        if (hooks == null) {
            return null;
        }
        // never throws into the class's load
        try {
            List<Finding> findings = check(className, hooks, classfileBuffer);
            Retransform pass = retransforming.get();
            if (pass != null && pass.internalName.equals(className)) {
                pass.seen = true;
                pass.findings.addAll(findings);
            } else {
                findings.forEach(f -> report(f.watched(), f.cause()));
            }
        } catch (Throwable t) {
            hooks.stream().filter(w -> w.mod().live()).forEach(w -> report(w, t));
        }
        return null;
    }

    private List<Finding> check(String className, List<Watched> hooks, byte[] bytes) {
        List<Watched> due = new ArrayList<>();
        for (Watched w : hooks) {
            // a mod out of the pass or stopped runs no hooks, and a stopped one has said so
            if (w.mod().live() && !reported.contains(w.key())) {
                due.add(w);
            }
        }
        List<Finding> findings = new ArrayList<>();
        if (due.isEmpty()) {
            return findings;
        }
        Map<String, Set<String>> modReads;
        try {
            Set<String> methods = new HashSet<>();
            due.forEach(w -> methods.add(w.hook().method() + w.hook().descriptor()));
            modReads = modReads(bytes, methods);
        } catch (Throwable t) {
            IllegalStateException cause = new IllegalStateException("cannot read the woven bytes of "
                    + className.replace('/', '.') + " to check them", t);
            due.forEach(w -> findings.add(new Finding(w, cause)));
            return findings;
        }
        for (Watched w : due) {
            Set<String> owners = modReads.get(w.hook().method() + w.hook().descriptor());
            if (owners == null) {
                // a missing method is a target mismatch, which the target check reports
                continue;
            }
            String patch = internalName(w.hook().patch());
            if (owners.stream().noneMatch(owner -> patch.startsWith(owner + "$"))) {
                findings.add(new Finding(w, new IllegalStateException(w.hook() + " runs without " + w.hook().patch()
                        + ": " + Loader.name() + " did not weave it (its log may say why)")));
            }
        }
        return findings;
    }

    private static Map<String, Set<String>> modReads(byte[] bytes, Set<String> methods) {
        Map<String, Set<String>> reads = new HashMap<>();
        ClassReader reader = OpenedClassReader.of(bytes);
        reader.accept(new ClassVisitor(OpenedClassReader.ASM_API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                if (!methods.contains(name + descriptor)) {
                    return null;
                }
                Set<String> owners = reads.computeIfAbsent(name + descriptor, k -> new HashSet<>());
                return new MethodVisitor(OpenedClassReader.ASM_API) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String fieldDescriptor) {
                        if (opcode == Opcodes.GETSTATIC && field.equals(MOD_FIELD)
                                && fieldDescriptor.equals(MOD_DESCRIPTOR)) {
                            owners.add(owner);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return reads;
    }

    private void report(Watched w, Throwable cause) {
        if (!reported.add(w.key())) {
            return;
        }
        String hookClass = w.listing().hookClass();
        String simpleName = hookClass.substring(Math.max(hookClass.lastIndexOf('.'), hookClass.lastIndexOf('$')) + 1);
        String what = "weaving " + simpleName + " into " + w.hook().owner() + "." + w.hook().method();
        ModState mod = w.mod();
        HookGate gate;
        try {
            gate = gate(mod, w.listing());
        } catch (Throwable t) {
            if (t != cause) {
                t.addSuppressed(cause);
            }
            mod.failed(mod.defaultGate(), "reaching " + w.listing().gate() + " to report an error while " + what, t);
            return;
        }
        mod.failed(gate, what, cause);
    }

    // the generated glue reads a named gate's INSTANCE the same way
    private HookGate gate(ModState mod, HookListing listing) throws ReflectiveOperationException {
        if (listing.defaultGate()) {
            return mod.defaultGate();
        }
        Object instance = Class.forName(listing.gate(), true, loader).getField("INSTANCE").get(null);
        if (!(instance instanceof HookGate gate)) {
            throw new IllegalStateException(listing.gate() + ".INSTANCE is not a HookGate: " + instance);
        }
        return gate;
    }

    private static String internalName(String binaryName) {
        return binaryName.replace('.', '/');
    }
}
