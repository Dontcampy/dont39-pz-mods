package com.pzlauncher.zabric.loader;

import com.pzlauncher.zabric.internal.Jars;
import com.pzlauncher.zabric.internal.MemberTypes;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.utility.OpenedClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads the member types of the classes a jar's patches name, before the loader applies them: it retransforms those
 * classes and describes them by reflection inside the retransformation, where a type that first loads passes the
 * access widener by ({@link MemberTypes}). Zabric's glue and hand-written patches alike. A target that is not loaded
 * yet stays so: the loader can still load it before it retransforms it (a patch's signature names it), so the types
 * its class file names are loaded instead.
 */
final class PatchTargets {
    private static final String PATCH = "Lme/zed_0xff/zombie_buddy/Patch;";
    // the loader refuses to patch its own classes
    private static final String OWN = "me.zed_0xff.";

    private PatchTargets() {
    }

    /**
     * Before the loader applies the patches of {@code jar} for {@code pkg}; {@code loader} serves the targets that are
     * not loaded yet. Throws after it did what it could.
     */
    static void prepare(Path jar, String pkg, Supplier<Class<?>[]> loaded, ClassLoader loader) throws IOException {
        // the loader stops before its patches for both
        if (pkg == null || pkg.isBlank() || !Files.isRegularFile(jar)) {
            return;
        }
        Set<String> targets = read(jar, pkg);
        if (!targets.isEmpty()) {
            loadMemberTypes(targets, loaded, loader);
        }
    }

    /**
     * The classes the patches in {@code jar} name, as the loader collects patches: classes in exactly {@code pkg} with
     * its {@code Patch} annotation. Name patterns are left out: they can name any number of loaded classes.
     */
    static Set<String> read(Path jar, String pkg) throws IOException {
        String dir = pkg.replace('.', '/') + "/";
        Set<String> targets = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.startsWith(dir) || !name.endsWith(".class") || name.indexOf('/', dir.length()) >= 0) {
                    continue;
                }
                String target;
                try {
                    target = target(Jars.bytes(zip, entry));
                } catch (IOException | RuntimeException e) {
                    throw new IOException(name + ": " + e, e);
                }
                if (target != null && !pattern(target) && !target.startsWith(OWN)) {
                    targets.add(target);
                }
            }
        } catch (IOException e) {
            throw new IOException("cannot read the patches in " + jar + ": " + e.getMessage(), e);
        }
        return targets;
    }

    /**
     * Loads the member types of each class in {@code loaded} that {@code targets} names, by reflection; for a target
     * not loaded, those its class file in {@code loader} names, through {@code loader}, leaving the target itself
     * unloaded. Repeats for targets that loaded meanwhile. Goes on after a class or type that fails, then throws the
     * first failure with the others suppressed. A target {@code loader} has no class file for is left to the loader.
     */
    static void loadMemberTypes(Set<String> targets, Supplier<Class<?>[]> loaded, ClassLoader loader) {
        Set<Class<?>> done = new HashSet<>();
        Set<String> doneUnloaded = new HashSet<>();
        List<IllegalStateException> failures = new ArrayList<>();
        boolean more = true;
        while (more) {
            more = false;
            Set<String> isLoaded = new HashSet<>();
            for (Class<?> c : loaded.get()) {
                if (!targets.contains(c.getName())) {
                    continue;
                }
                isLoaded.add(c.getName());
                if (!done.add(c)) {
                    continue;
                }
                more = true;
                try {
                    MemberTypes.load(c);
                } catch (Throwable t) {
                    failures.add(new IllegalStateException("cannot load the member types of " + c.getName() + ": "
                            + t, t));
                }
            }
            for (String target : targets) {
                if (!isLoaded.contains(target) && doneUnloaded.add(target)) {
                    more = true;
                    loadUnloaded(target, loader, failures);
                }
            }
        }
        if (!failures.isEmpty()) {
            IllegalStateException failure = failures.get(0);
            failures.subList(1, failures.size()).forEach(failure::addSuppressed);
            throw failure;
        }
    }

    // what reflection on the target would load: signature and exception types, nest host and members
    private static void loadUnloaded(String target, ClassLoader loader, List<IllegalStateException> failures) {
        Set<String> types;
        try {
            byte[] bytes = classFile(target, loader);
            if (bytes == null) {
                return;
            }
            Members members = members(bytes);
            types = members.types;
            if (members.nestHost != null) {
                types.add(members.nestHost);
                byte[] host = classFile(members.nestHost, loader);
                if (host == null) {
                    throw new IllegalStateException("no class file for its nest host " + members.nestHost);
                }
                types.addAll(members(host).nestMembers);
            }
        } catch (Throwable t) {
            failures.add(new IllegalStateException("cannot read the member types of " + target + ": " + t, t));
            return;
        }
        types.remove(target);
        for (String type : types) {
            try {
                Class.forName(type, false, loader);
            } catch (Throwable t) {
                failures.add(new IllegalStateException("cannot load " + type + ", a member type of " + target
                        + ": " + t, t));
            }
        }
    }

    private static byte[] classFile(String binaryName, ClassLoader loader) throws IOException {
        try (InputStream in = loader.getResourceAsStream(binaryName.replace('.', '/') + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    // binary names
    private static final class Members {
        final Set<String> types = new LinkedHashSet<>();
        final Set<String> nestMembers = new LinkedHashSet<>();
        String nestHost;
    }

    private static Members members(byte[] bytes) {
        Members members = new Members();
        OpenedClassReader.of(bytes).accept(new ClassVisitor(OpenedClassReader.ASM_API) {
            private String self;

            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                              String[] interfaces) {
                self = name;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                                           Object value) {
                add(Type.getType(descriptor));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                // reflection does not list the static initializer
                if (!name.equals("<clinit>")) {
                    Type method = Type.getMethodType(descriptor);
                    add(method.getReturnType());
                    for (Type argument : method.getArgumentTypes()) {
                        add(argument);
                    }
                    if (exceptions != null) {
                        for (String exception : exceptions) {
                            members.types.add(binaryName(exception));
                        }
                    }
                }
                return null;
            }

            @Override
            public void visitNestHost(String nestHost) {
                members.nestHost = binaryName(nestHost);
            }

            @Override
            public void visitNestMember(String nestMember) {
                members.types.add(binaryName(nestMember));
                members.nestMembers.add(binaryName(nestMember));
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                if (self.equals(outerName)) {
                    members.types.add(binaryName(name));
                }
            }

            private void add(Type type) {
                Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
                if (element.getSort() == Type.OBJECT) {
                    members.types.add(element.getClassName());
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return members;
    }

    private static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    // the loader matches these by name: see its SyntaxSugar
    private static boolean pattern(String target) {
        return target.startsWith("*") || target.endsWith("*") || (target.startsWith("/") && target.endsWith("/"));
    }

    private static String target(byte[] bytes) {
        String[] target = new String[1];
        OpenedClassReader.of(bytes).accept(new ClassVisitor(OpenedClassReader.ASM_API) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!descriptor.equals(PATCH)) {
                    return null;
                }
                return new AnnotationVisitor(OpenedClassReader.ASM_API) {
                    @Override
                    public void visit(String name, Object value) {
                        if (name.equals("className") && value instanceof String s) {
                            target[0] = s;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return target[0];
    }
}
