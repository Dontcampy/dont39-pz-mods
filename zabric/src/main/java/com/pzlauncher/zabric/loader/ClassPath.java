package com.pzlauncher.zabric.loader;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which jar a class loader takes a package's classes from, for a fixed set of jars; the loader's records name jars, not
 * packages.
 */
public final class ClassPath {
    private final ClassLoader loader;
    // package folder → jar → its classes in that folder
    private final Map<String, Map<Path, List<String>>> classes;

    private ClassPath(ClassLoader loader, Map<String, Map<Path, List<String>>> classes) {
        this.loader = loader;
        this.classes = classes;
    }

    static ClassPath of(Collection<Path> jars, ClassLoader loader) throws IOException {
        Map<String, Map<Path, List<String>>> classes = new HashMap<>();
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    String name = entry.getName();
                    int slash = name.lastIndexOf('/');
                    if (slash > 0 && name.endsWith(".class")) {
                        classes.computeIfAbsent(name.substring(0, slash + 1), k -> new LinkedHashMap<>())
                                .computeIfAbsent(jar, k -> new ArrayList<>()).add(name);
                    }
                }
            } catch (IOException e) {
                throw new IOException("cannot list the classes of " + jar + ": " + e.getMessage(), e);
            }
        }
        return new ClassPath(loader, classes);
    }

    /**
     * Which jar the class loader serves the classes of {@code pkg} (exactly that package) from, or null when none of
     * the jars holds a class of it. Throws when they are served from more than one jar, or from a jar the class loader
     * does not name.
     */
    public Path servingJar(String pkg) throws IOException {
        if (pkg.isEmpty() || pkg.startsWith(".") || pkg.endsWith(".") || pkg.contains("/")) {
            throw new IllegalArgumentException("'" + pkg + "' is not a Java package name");
        }
        // every class: a jar later on the search path still serves the classes the earlier ones lack
        Set<Path> servedFrom = new LinkedHashSet<>();
        for (Map.Entry<Path, List<String>> jar : classes.getOrDefault(pkg.replace('.', '/') + "/", Map.of())
                .entrySet()) {
            for (String entry : jar.getValue()) {
                URL url = loader.getResource(entry);
                if (url == null) {
                    throw new IllegalStateException("the class loader does not find " + entry + ", which "
                            + jar.getKey() + " holds");
                }
                servedFrom.add(jarOf(url));
            }
        }
        if (servedFrom.size() > 1) {
            throw new IllegalStateException("the classes of " + pkg + " are served from more than one jar: "
                    + servedFrom);
        }
        return servedFrom.isEmpty() ? null : servedFrom.iterator().next();
    }

    private static Path jarOf(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        if (!(connection instanceof JarURLConnection jar)) {
            throw new IllegalStateException("the class loader serves " + url + ", which is not in a jar");
        }
        try {
            // the loader records real paths
            return Path.of(jar.getJarFileURL().toURI()).toRealPath();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot tell the jar of " + url, e);
        }
    }
}
