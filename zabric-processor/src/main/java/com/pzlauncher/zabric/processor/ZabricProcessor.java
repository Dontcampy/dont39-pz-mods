package com.pzlauncher.zabric.processor;

import com.pzlauncher.zabric.processor.glue.GlueModel;
import com.pzlauncher.zabric.processor.glue.GlueWriter;
import com.pzlauncher.zabric.processor.glue.HookAnalyzer;
import com.pzlauncher.zabric.processor.glue.HookDecls.HookClass;
import com.pzlauncher.zabric.processor.glue.Messages;
import com.pzlauncher.zabric.processor.glue.Names;
import com.pzlauncher.zabric.processor.glue.Reporter;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.pzlauncher.zabric.processor.JavacHookReader.HANDLERS;
import static com.pzlauncher.zabric.processor.JavacHookReader.PARAMS;
import static com.pzlauncher.zabric.processor.JavacHookReader.mirror;

/**
 * Zabric's javac annotation processor. Options: {@value Names#OPTION_MOD_ID} and {@value Names#OPTION_JAVA_PKG_NAME}
 * (required with hook classes), {@value Names#OPTION_GLUE_CLASS} (outer glue class name; default: the mod id in
 * PascalCase + {@code Glue}).
 */
public final class ZabricProcessor extends AbstractProcessor {
    /** Outer glue classes this processor wrote; their @Patch classes are not the author's. */
    private final Set<String> generated = new HashSet<>();
    private JavacTypes types;
    private boolean hooksDone;
    private int errors;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        types = new JavacTypes(env);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> names = new LinkedHashSet<>(List.of(Names.HOOKS, Names.UNSAFE_LOADER_PATCH, Names.PATCH));
        names.addAll(HANDLERS);
        names.addAll(PARAMS);
        return names;
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(Names.OPTION_MOD_ID, Names.OPTION_JAVA_PKG_NAME, Names.OPTION_GLUE_CLASS);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        checkLoaderPatches(round);
        checkStray(round);
        List<TypeElement> hookClasses = annotated(round, Names.HOOKS).stream()
                .filter(e -> e instanceof TypeElement).map(e -> (TypeElement) e).toList();
        if (!hookClasses.isEmpty()) {
            if (hooksDone) {
                // one outer glue class per compilation
                hookClasses.forEach(c -> error(c, Messages.laterRound(c.getQualifiedName().toString())));
            } else {
                hooksDone = true;
                generate(hookClasses);
            }
        }
        return false;
    }

    private void checkLoaderPatches(RoundEnvironment round) {
        for (Element e : annotated(round, Names.PATCH)) {
            if (!(e instanceof TypeElement type) || generated.contains(outermost(type).getQualifiedName().toString())) {
                continue;
            }
            String name = type.getQualifiedName().toString();
            if (mirror(type, Names.UNSAFE_LOADER_PATCH) == null) {
                error(type, Messages.loaderPatch(name));
            } else {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, Messages.unsafeLoaderPatch(name),
                        type);
            }
        }
        for (Element e : annotated(round, Names.UNSAFE_LOADER_PATCH)) {
            if (e instanceof TypeElement type && mirror(type, Names.PATCH) == null) {
                error(type, Messages.markerWithoutPatch(type.getQualifiedName().toString()));
            }
        }
    }

    private static TypeElement outermost(TypeElement type) {
        TypeElement outer = type;
        while (outer.getEnclosingElement() instanceof TypeElement enclosing) {
            outer = enclosing;
        }
        return outer;
    }

    private void checkStray(RoundEnvironment round) {
        for (String name : HANDLERS) {
            for (Element e : annotated(round, name)) {
                if (mirror(e.getEnclosingElement(), Names.HOOKS) == null) {
                    error(e, Messages.handlerOutsideHooks(e.getEnclosingElement().getSimpleName() + "."
                            + e.getSimpleName(), "@" + simpleName(name)));
                }
            }
        }
        for (String name : PARAMS) {
            for (Element e : annotated(round, name)) {
                Element method = e.getEnclosingElement();
                if (HANDLERS.stream().noneMatch(h -> mirror(method, h) != null)) {
                    error(e, Messages.parameterOutsideHandler(method.getEnclosingElement().getSimpleName() + "."
                            + method.getSimpleName(), "@" + simpleName(name)));
                }
            }
        }
    }

    private void generate(List<TypeElement> hookClasses) {
        Map<String, String> options = processingEnv.getOptions();
        String modId = options.get(Names.OPTION_MOD_ID);
        String pkg = options.get(Names.OPTION_JAVA_PKG_NAME);
        boolean missing = false;
        for (String option : List.of(Names.OPTION_MOD_ID, Names.OPTION_JAVA_PKG_NAME)) {
            if (options.get(option) == null || options.get(option).isBlank()) {
                error(null, Messages.missingOption(option));
                missing = true;
            }
        }
        if (missing) {
            return;
        }
        String className = options.getOrDefault(Names.OPTION_GLUE_CLASS, defaultClassName(modId));
        int before = errors;
        JavacHookReader reader = new JavacHookReader(processingEnv, types, this::error);
        List<HookClass<TypeMirror>> decls = new ArrayList<>();
        for (TypeElement hookClass : hookClasses) {
            HookClass<TypeMirror> decl = reader.read(hookClass);
            if (decl != null) {
                decls.add(decl);
            }
        }
        Reporter reporter = (node, message) -> error((Element) node, message);
        GlueModel.Unit unit = new HookAnalyzer<>(types, reporter).analyze(modId, pkg, className, decls);
        if (unit == null || errors != before) {
            return;
        }
        Element[] origins = hookClasses.toArray(new Element[0]);
        try {
            try (Writer out = processingEnv.getFiler().createSourceFile(unit.qualifiedName(), origins).openWriter()) {
                out.write(GlueWriter.source(unit));
            }
            for (var listing : GlueWriter.listings(unit).entrySet()) {
                try (OutputStream out = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                        listing.getKey(), origins).openOutputStream()) {
                    out.write(listing.getValue().getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            error(null, "cannot write the hook glue " + unit.qualifiedName() + ": " + e);
            return;
        }
        generated.add(unit.qualifiedName());
    }

    /** {@code my-mod} -> {@code MyModGlue}. */
    static String defaultClassName(String modId) {
        StringBuilder name = new StringBuilder();
        for (String part : modId.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            name.insert(0, '_');
        }
        return name.append("Glue").toString();
    }

    private Set<? extends Element> annotated(RoundEnvironment round, String annotation) {
        TypeElement type = processingEnv.getElementUtils().getTypeElement(annotation);
        return type == null ? Set.of() : round.getElementsAnnotatedWith(type);
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private void error(Element element, String message) {
        errors++;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
