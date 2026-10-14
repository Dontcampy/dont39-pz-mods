package com.pzlauncher.zabric.processor;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles sources in process with {@link ZabricProcessor}, against the test class path (Zabric's hook API and
 * ZombieBuddy's jar).
 */
final class Compiler {
    static final String MOD_ID = "test-mod";
    static final String PKG = "glue";
    static final Map<String, String> OPTIONS = Map.of("zabric.modId", MOD_ID, "zabric.javaPkgName", PKG);

    private Compiler() {
    }

    record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path generated,
                  Path classes) {
        List<String> errors() {
            return messages(Diagnostic.Kind.ERROR);
        }

        List<String> warnings() {
            return messages(Diagnostic.Kind.WARNING);
        }

        private List<String> messages(Diagnostic.Kind kind) {
            return diagnostics.stream().filter(d -> d.getKind() == kind).map(d -> d.getMessage(Locale.ROOT)).toList();
        }

        /** A generated source, or null. */
        String source(String relative) {
            return read(generated.resolve(relative));
        }

        /** A generated resource, or null. */
        String resource(String relative) {
            return read(classes.resolve(relative));
        }

        boolean generatedAnything() throws IOException {
            try (var files = Files.walk(generated)) {
                return files.anyMatch(Files::isRegularFile);
            }
        }

        private static String read(Path file) {
            try {
                return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Sources as pairs of file path (e.g. {@code t/Target.java}) and text. */
    static Result compile(Path root, Map<String, String> options, String... pathAndText) {
        try {
            List<Path> files = new ArrayList<>();
            for (int i = 0; i < pathAndText.length; i += 2) {
                Path file = root.resolve("src").resolve(pathAndText[i]);
                Files.createDirectories(file.getParent());
                Files.writeString(file, pathAndText[i + 1], StandardCharsets.UTF_8);
                files.add(file);
            }
            return compile(root, options, files);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Result compile(Path root, Map<String, String> options, List<Path> files) throws IOException {
        Path generated = Files.createDirectories(root.resolve("generated"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> args = new ArrayList<>(List.of("--release", "17", "-proc:full", "-d", classes.toString(), "-s",
                generated.toString(), "-classpath", System.getProperty("java.class.path")));
        options.forEach((k, v) -> args.add("-A" + k + "=" + v));
        try (StandardJavaFileManager fileManager = javac.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            JavaCompiler.CompilationTask task = javac.getTask(null, fileManager, diagnostics, args, null,
                    fileManager.getJavaFileObjectsFromPaths(files));
            task.setProcessors(List.of(new ZabricProcessor()));
            boolean success = task.call();
            return new Result(success, diagnostics.getDiagnostics(), generated, classes);
        }
    }
}
