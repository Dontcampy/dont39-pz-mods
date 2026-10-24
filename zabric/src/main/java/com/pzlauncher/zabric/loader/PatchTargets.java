package com.pzlauncher.zabric.loader;

import com.pzlauncher.zabric.internal.Jars;
import com.pzlauncher.zabric.internal.MemberTypes;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.utility.OpenedClassReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads the member types of the loaded classes a jar's patches name, before the loader applies them: it retransforms
 * those classes and describes them by reflection inside the retransformation, where a type that first loads passes
 * the access widener by ({@link MemberTypes}). Zabric's glue and hand-written patches alike.
 */
final class PatchTargets {
    private static final String PATCH = "Lme/zed_0xff/zombie_buddy/Patch;";
    // the loader refuses to patch its own classes
    private static final String OWN = "me.zed_0xff.";

    private PatchTargets() {
    }

    /**
     * Before the loader applies the patches of {@code jar} for {@code pkg}. Leaves classes that are not loaded alone:
     * the loader weaves those from their bytes. Throws after it did what it could.
     */
    static void prepare(Path jar, String pkg, Supplier<Class<?>[]> loaded) throws IOException {
        // the loader stops before its patches for both
        if (pkg == null || pkg.isBlank() || !Files.isRegularFile(jar)) {
            return;
        }
        Set<String> targets = read(jar, pkg);
        if (!targets.isEmpty()) {
            loadMemberTypes(targets, loaded);
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
     * Loads the member types of each class in {@code loaded} that {@code targets} names, then of those that loaded
     * meanwhile. Goes on after a class that fails, then throws the first failure with the others suppressed.
     */
    static void loadMemberTypes(Set<String> targets, Supplier<Class<?>[]> loaded) {
        Set<Class<?>> done = new HashSet<>();
        IllegalStateException failure = null;
        boolean more = true;
        while (more) {
            more = false;
            for (Class<?> c : loaded.get()) {
                if (!targets.contains(c.getName()) || !done.add(c)) {
                    continue;
                }
                more = true;
                try {
                    MemberTypes.load(c);
                } catch (Throwable t) {
                    IllegalStateException e = new IllegalStateException("cannot load the member types of "
                            + c.getName() + ": " + t, t);
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
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
