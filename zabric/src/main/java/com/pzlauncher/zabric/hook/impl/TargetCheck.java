package com.pzlauncher.zabric.hook.impl;

import com.pzlauncher.zabric.hook.impl.HookListing.Hook;
import com.pzlauncher.zabric.internal.Jars;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.utility.OpenedClassReader;

import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Whether the classes and methods a listing names are there at runtime as the hook class was compiled against. */
final class TargetCheck {
    private TargetCheck() {
    }

    // from the class's own jar: all mod jars share one class loader, so a resource lookup could find another mod's
    static String problem(Class<?> hookClass) {
        Path location;
        try {
            location = Jars.codeSource(hookClass);
        } catch (URISyntaxException | RuntimeException e) {
            return "cannot tell where " + hookClass.getName() + " was loaded from: " + e;
        }
        return problem(hookClass, location);
    }

    static String gateProblem(Class<?> gate) {
        Path location;
        try {
            location = Jars.codeSource(gate);
        } catch (URISyntaxException | RuntimeException e) {
            return "cannot tell where " + gate.getName() + " was loaded from: " + e;
        }
        return gateProblem(gate, location);
    }

    static String gateProblem(Class<?> gate, Path location) {
        List<String> problems = new ArrayList<>();
        try {
            for (HookListing listing : HookListing.readAll(location)) {
                if (listing.gate().equals(gate.getName())) {
                    String problem = problem(listing, gate.getClassLoader());
                    if (problem != null) {
                        problems.add(problem);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return "cannot read the hook listings in " + location + ": " + e;
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    static String problem(Class<?> hookClass, Path location) {
        HookListing listing;
        try {
            listing = HookListing.read(location, hookClass.getName());
        } catch (IOException | RuntimeException e) {
            return "cannot read the hook listing of " + hookClass.getName() + ": " + e;
        }
        return problem(listing, hookClass.getClassLoader());
    }

    static String problem(HookListing listing, ClassLoader loader) {
        List<String> wrong = new ArrayList<>();
        for (String patch : listing.patches()) {
            try {
                Class.forName(patch, false, loader);
            } catch (ClassNotFoundException e) {
                wrong.add(patchMissing(patch));
            } catch (LinkageError e) {
                wrong.add("its patch class " + patch + " cannot be loaded: " + e);
            }
        }
        for (Hook hook : listing.hooks()) {
            String why;
            try {
                why = missing(hook, descriptors(Class.forName(hook.owner(), false, loader), hook));
            } catch (ClassNotFoundException e) {
                why = classMissing(hook);
            } catch (LinkageError e) {
                why = "cannot load " + hook.owner() + " or read its methods: " + e;
            }
            if (why != null) {
                wrong.add(why);
            }
        }
        return mismatch(listing, wrong);
    }

    /**
     * The classes of {@link #problem(HookListing, ClassLoader)}, by their class files only: a class loaded here could
     * load before the game loads it. Methods are checked by {@link #problem(HookListing, String, byte[])} as each
     * target loads.
     */
    static String presenceProblem(HookListing listing, ClassLoader loader) {
        List<String> wrong = new ArrayList<>();
        for (String patch : listing.patches()) {
            if (!present(patch, loader)) {
                wrong.add(patchMissing(patch));
            }
        }
        for (Hook hook : listing.hooks()) {
            if (!present(hook.owner(), loader)) {
                wrong.add(classMissing(hook));
            }
        }
        return mismatch(listing, wrong);
    }

    /** The methods of {@link #problem(HookListing, ClassLoader)} for the hooks on {@code owner}, from its bytes. */
    static String problem(HookListing listing, String owner, byte[] bytes) {
        Map<String, List<String>> byName = new HashMap<>();
        OpenedClassReader.of(bytes).accept(new ClassVisitor(OpenedClassReader.ASM_API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                // reflection does not list the static initializer either
                if (!name.equals("<clinit>")) {
                    byName.computeIfAbsent(name, k -> new ArrayList<>()).add(descriptor);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> wrong = new ArrayList<>();
        for (Hook hook : listing.hooks()) {
            if (hook.owner().equals(owner)) {
                String why = missing(hook, byName.getOrDefault(hook.method(), List.of()));
                if (why != null) {
                    wrong.add(why);
                }
            }
        }
        return mismatch(listing, wrong);
    }

    private static boolean present(String binaryName, ClassLoader loader) {
        return loader.getResource(binaryName.replace('.', '/') + ".class") != null;
    }

    private static String patchMissing(String patch) {
        return "its patch class " + patch + " is not there";
    }

    private static String classMissing(Hook hook) {
        return "class " + hook.owner() + " is not there";
    }

    private static String mismatch(HookListing listing, List<String> wrong) {
        return wrong.isEmpty() ? null : "the targets of " + listing.hookClass() + " (" + listing.source()
                + ") do not match: " + String.join("; ", wrong);
    }

    private static List<String> descriptors(Class<?> owner, Hook hook) {
        List<String> same = new ArrayList<>();
        if (hook.method().equals("<init>")) {
            for (Constructor<?> c : owner.getDeclaredConstructors()) {
                same.add(MethodType.methodType(void.class, c.getParameterTypes()).toMethodDescriptorString());
            }
        } else {
            for (Method m : owner.getDeclaredMethods()) {
                if (m.getName().equals(hook.method())) {
                    same.add(MethodType.methodType(m.getReturnType(), m.getParameterTypes())
                            .toMethodDescriptorString());
                }
            }
        }
        return same;
    }

    // same: the descriptors of the owner's methods of the hook's name
    private static String missing(Hook hook, List<String> same) {
        if (same.contains(hook.descriptor())) {
            return null;
        }
        return same.isEmpty() ? hook.owner() + " has no method " + hook.method() : hook.owner() + " has no "
                + hook.method() + hook.descriptor() + " (it has " + hook.method() + String.join(", " + hook.method(),
                same) + ")";
    }
}
