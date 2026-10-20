package com.pzlauncher.zabric.loader;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Which jar a class loader takes a package's classes from; the loader's records name jars, not packages. */
final class ClassPath {
    private ClassPath() {
    }

    static Path servingJar(String pkg, Collection<Path> jars, ClassLoader loader) throws IOException {
        if (pkg.isEmpty() || pkg.startsWith(".") || pkg.endsWith(".") || pkg.contains("/")) {
            throw new IllegalArgumentException("'" + pkg + "' is not a Java package name");
        }
        String dir = pkg.replace('.', '/') + "/";
        // every class: a jar later on the search path still serves the classes the earlier ones lack
        Set<Path> servedFrom = new LinkedHashSet<>();
        for (Path jar : jars) {
            for (String entry : classes(jar, dir)) {
                URL url = loader.getResource(entry);
                if (url == null) {
                    throw new IllegalStateException("the class loader does not find " + entry + ", which " + jar
                            + " holds");
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

    private static List<String> classes(Path jar, String dir) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return Collections.list(zip.entries()).stream()
                    .map(ZipEntry::getName)
                    .filter(n -> n.startsWith(dir) && n.endsWith(".class") && n.indexOf('/', dir.length()) < 0)
                    .toList();
        }
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
