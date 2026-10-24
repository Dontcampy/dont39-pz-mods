package com.pzlauncher.zabric.loader;

import com.pzlauncher.zabric.hook.weave.Weaving;
import com.pzlauncher.zabric.internal.Jars;
import com.pzlauncher.zabric.loader.prep.PrepGlue;
import me.zed_0xff.zombie_buddy.Patch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchTargetsTest {
    private static final String PREP = "com.pzlauncher.zabric.loader.prep";
    private static final String UNPREP = "com.pzlauncher.zabric.loader.unprep";
    private static final String MEMBERS = "com.pzlauncher.zabric.loader.members";

    @TempDir
    Path dir;

    @Test
    void readsTheClassesThePatchesInExactlyThePackageName() throws Exception {
        Map<String, Class<?>> entries = new LinkedHashMap<>();
        entries.put("a/b/Glue$Exact.class", Exact.class);
        entries.put("a/b/Glue$Nested.class", Nested.class);
        entries.put("a/b/Glue$Pattern.class", Pattern.class);
        entries.put("a/b/Glue$Regex.class", Regex.class);
        entries.put("a/b/Glue$Own.class", Own.class);
        entries.put("a/b/Plain.class", Plain.class);
        entries.put("a/b/sub/Glue$Sub.class", Sub.class);
        entries.put("a/Glue$Parent.class", Parent.class);
        Path jar = jar(entries);
        assertEquals(Set.of("zombie.Exact", "zombie.Outer$Nested"), PatchTargets.read(jar, "a.b"));
    }

    @Test
    void aClassThatDoesNotReadIsAnErrorNamingJarAndEntry() throws Exception {
        Path jar = dir.resolve("broken.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("a/b/Broken.class"));
            zip.write(new byte[]{1, 2, 3});
        }
        IOException e = assertThrows(IOException.class, () -> PatchTargets.read(jar, "a.b"));
        assertTrue(e.getMessage().contains(jar.toString()) && e.getMessage().contains("a/b/Broken.class"),
                e.getMessage());
    }

    @Test
    void whatTheLoaderDoesNotPatchIsNotRead() throws Exception {
        Path notAJar = Files.writeString(dir.resolve("not.jar"), "no jar");
        PatchTargets.prepare(notAJar, "", () -> {
            throw new AssertionError("a blank package has no patches");
        }, getClass().getClassLoader());
        PatchTargets.prepare(dir.resolve("gone.jar"), "a.b", () -> {
            throw new AssertionError("the loader reports a missing jar itself");
        }, getClass().getClassLoader());
    }

    // the cause, as the loader weaves a loaded class: a type it first loads there passes every transformer by
    @Test
    void withoutPreparingAFieldTypeLoadsUnseen() throws Exception {
        FreshLoader targets = new FreshLoader(UNPREP + ".target.");
        Recorder recorder = new Recorder(targets);
        try (Recorder r = recorder.install()) {
            targets.define(UNPREP + ".target.Host");
            Weaving.weave(UNPREP);
        }
        assertEquals(List.of(UNPREP + ".target.Host"), recorder.seen);
        assertTrue(targets.defined.contains(UNPREP + ".target.FieldType"), "the weave loaded it");
    }

    @Test
    void preparedMemberTypesLoadWhereTheTransformersSeeThem() throws Exception {
        String target = PREP + ".target.";
        FreshLoader targets = new FreshLoader(target);
        Recorder recorder = new Recorder(targets);
        try (Recorder r = recorder.install()) {
            targets.define(target + "Host");
            PatchTargets.prepare(glueJar(), PREP, () -> loadedBy(targets), targets);
            Weaving.weave(PREP);
            assertFalse(targets.defined.contains(target + "Unloaded"), "a target that is not loaded stays so");
            // as the game loads it later
            targets.define(target + "Unloaded");
        }
        for (String name : List.of("FieldType", "ParamType", "ReturnType", "Host$Member", "Second", "SecondField")) {
            assertTrue(recorder.seen.contains(target + name), name + " in " + recorder.seen);
        }
        int unloaded = recorder.seen.indexOf(target + "Unloaded");
        for (String name : List.of("UnloadedField", "UnloadedParam", "UnloadedReturn", "UnloadedException",
                "Unloaded$Member")) {
            int at = recorder.seen.indexOf(target + name);
            assertTrue(at >= 0 && at < unloaded, name + " before Unloaded in " + recorder.seen);
        }
    }

    @Test
    void aClassThatFailsIsReportedAfterTheRest() {
        FreshLoader loader = new FreshLoader(MEMBERS + ".", MEMBERS + ".Missing");
        Class<?> holder = loader.define(MEMBERS + ".Holder");
        Class<?> good = loader.define(MEMBERS + ".Good");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PatchTargets.loadMemberTypes(
                Set.of(holder.getName(), good.getName()), () -> new Class<?>[]{holder, good}, loader));
        assertTrue(e.getMessage().startsWith("cannot load the member types of " + MEMBERS + ".Holder: "
                + NoClassDefFoundError.class.getName()), e.getMessage());
        assertTrue(loader.defined.contains(MEMBERS + ".GoodField"), loader.defined.toString());
    }

    @Test
    void aTypeThatFailsInAClassFileIsReportedAfterTheRest() {
        FreshLoader loader = new FreshLoader(MEMBERS + ".", MEMBERS + ".Missing");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PatchTargets.loadMemberTypes(
                Set.of(MEMBERS + ".Holder", MEMBERS + ".Good", "zombie.NotThere"), () -> new Class<?>[0], loader));
        assertTrue(e.getMessage().startsWith("cannot load " + MEMBERS + ".Missing, a member type of " + MEMBERS
                + ".Holder: "), e.getMessage());
        assertEquals(Set.of(MEMBERS + ".GoodField"), loader.defined, "the targets stay unloaded");
    }

    private static Class<?>[] loadedBy(ClassLoader loader) {
        return Arrays.stream(Weaving.instrumentation().getAllLoadedClasses())
                .filter(c -> c.getClassLoader() == loader)
                .toArray(Class<?>[]::new);
    }

    // the test's class files of the glue package, as a mod's jar
    private Path glueJar() throws Exception {
        Path root = Jars.codeSource(PrepGlue.class);
        Path pkg = root.resolve(PREP.replace('.', '/'));
        Path jar = dir.resolve("prep.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.walk(pkg)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
            }
        }
        return jar;
    }

    private Path jar(Map<String, Class<?>> entries) throws IOException {
        Path jar = dir.resolve("m.jar");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, Class<?>> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(bytes(e.getValue()));
            }
        }
        return jar;
    }

    private static byte[] bytes(Class<?> c) throws IOException {
        try (InputStream in = c.getClassLoader().getResourceAsStream(c.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    // stands in for the access widener: a transformer of the loader's agent
    private static final class Recorder implements ClassFileTransformer, AutoCloseable {
        // in load order
        final List<String> seen = new CopyOnWriteArrayList<>();
        private final ClassLoader of;

        Recorder(ClassLoader of) {
            this.of = of;
        }

        Recorder install() {
            Weaving.instrumentation().addTransformer(this, true);
            return this;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
                                byte[] bytes) {
            if (redefined == null && loader == of && className != null) {
                seen.add(className.replace('/', '.'));
            }
            return null;
        }

        @Override
        public void close() {
            Weaving.instrumentation().removeTransformer(this);
        }
    }

    // defines the test's classes under prefix itself: the test runner has loaded its own copies of them all
    private static final class FreshLoader extends ClassLoader {
        final Set<String> defined = ConcurrentHashMap.newKeySet();
        private final String prefix;
        private final Set<String> refused;

        FreshLoader(String prefix, String... refused) {
            super(PatchTargetsTest.class.getClassLoader());
            this.prefix = prefix;
            this.refused = Set.of(refused);
        }

        Class<?> define(String name) {
            try {
                return loadClass(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(prefix)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                return c != null ? c : findClass(name);
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (refused.contains(name)) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes;
            try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
            defined.add(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Patch(className = "zombie.Exact", methodName = "m")
    private static final class Exact {
    }

    @Patch(className = "zombie.Outer$Nested", methodName = "m")
    private static final class Nested {
    }

    @Patch(className = "zombie.*", methodName = "m")
    private static final class Pattern {
    }

    @Patch(className = "/zombie\\..*/", methodName = "m")
    private static final class Regex {
    }

    @Patch(className = "me.zed_0xff.zombie_buddy.Loader", methodName = "m")
    private static final class Own {
    }

    private static final class Plain {
    }

    @Patch(className = "zombie.Sub", methodName = "m")
    private static final class Sub {
    }

    @Patch(className = "zombie.Parent", methodName = "m")
    private static final class Parent {
    }
}
