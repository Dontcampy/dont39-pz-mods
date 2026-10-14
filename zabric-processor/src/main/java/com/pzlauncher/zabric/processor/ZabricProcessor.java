package com.pzlauncher.zabric.processor;

import com.pzlauncher.zabric.processor.glue.GlueModel;
import com.pzlauncher.zabric.processor.glue.GlueWriter;
import com.pzlauncher.zabric.processor.glue.HookAnalyzer;
import com.pzlauncher.zabric.processor.glue.HookDecls;
import com.pzlauncher.zabric.processor.glue.HookDecls.Handler;
import com.pzlauncher.zabric.processor.glue.HookDecls.HookClass;
import com.pzlauncher.zabric.processor.glue.HookDecls.Kind;
import com.pzlauncher.zabric.processor.glue.HookDecls.Member;
import com.pzlauncher.zabric.processor.glue.HookDecls.Param;
import com.pzlauncher.zabric.processor.glue.HookDecls.TargetClass;
import com.pzlauncher.zabric.processor.glue.Messages;
import com.pzlauncher.zabric.processor.glue.Names;
import com.pzlauncher.zabric.processor.glue.Reporter;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Zabric's annotation processor: checks the {@code @Hooks} classes of a compilation against the compile classpath and
 * writes their loader glue and listings ({@link GlueWriter}). Options: {@value Names#OPTION_MOD_ID} and
 * {@value Names#OPTION_JAVA_PKG_NAME} (required with hook classes), {@value Names#OPTION_GLUE_CLASS} (the outer glue
 * class's simple name; default: the mod id in PascalCase + {@code Glue}, e.g. {@code MyModGlue} for {@code my-mod}).
 * Also rejects ZombieBuddy {@code @Patch} classes without {@code @UnsafeLoaderPatch}.
 */
public final class ZabricProcessor extends AbstractProcessor {
    private static final List<String> HANDLERS = List.of(Names.INJECT, Names.MODIFY_RETURN_VALUE,
            Names.MODIFY_VARIABLE);
    private static final List<String> PARAMS = List.of(Names.SELF, Names.SHARE, Names.THROWN);

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
                hookClasses.forEach(c -> error(c, c.getQualifiedName() + ": @Hooks classes generated in a later"
                        + " processing round are not supported"));
            } else {
                hooksDone = true;
                generate(hookClasses);
            }
        }
        return false;
    }

    // ---- D9: hand-written loader patches ----

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

    /** Handler annotations outside hook classes, parameter annotations outside handlers. */
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

    // ---- hooks ----

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
        List<HookClass<TypeMirror>> decls = new ArrayList<>();
        for (TypeElement hookClass : hookClasses) {
            HookClass<TypeMirror> decl = read(hookClass);
            if (decl != null) {
                decls.add(decl);
            }
        }
        Reporter reporter = new Reporter() {
            @Override
            public void error(Object node, String message) {
                ZabricProcessor.this.error((Element) node, message);
            }

            @Override
            public void warning(Object node, String message) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, message, (Element) node);
            }
        };
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
        if (name.length() == 0 || !Character.isJavaIdentifierStart(name.charAt(0))) {
            name.insert(0, '_');
        }
        return name.append("Glue").toString();
    }

    /** The declaration of a hook class, or null after reporting what keeps it from being one. */
    private HookClass<TypeMirror> read(TypeElement hookClass) {
        String name = hookClass.getQualifiedName().toString();
        boolean ok = true;
        if (hookClass.getKind() != ElementKind.CLASS) {
            error(hookClass, name + ": @Hooks is for classes");
            ok = false;
        }
        for (Element e = hookClass; e instanceof TypeElement t; e = e.getEnclosingElement()) {
            boolean nested = t.getEnclosingElement() instanceof TypeElement;
            if (!t.getModifiers().contains(Modifier.PUBLIC) || nested && !t.getModifiers().contains(Modifier.STATIC)) {
                error(hookClass, name + ": a hook class must be public (and static when nested): the glue calls its"
                        + " handlers from another package");
                ok = false;
                break;
            }
        }
        ok &= checkMixin(hookClass, name);

        AnnotationMirror hooks = mirror(hookClass, Names.HOOKS);
        Map<String, AnnotationValue> values = values(hooks);
        List<TargetClass<TypeMirror>> targets = new ArrayList<>();
        for (AnnotationValue value : list(values.get("value"))) {
            TypeMirror type = (TypeMirror) value.getValue();
            if (type.getKind() != TypeKind.DECLARED) {
                // javac reports an unresolved class literal itself
                ok = false;
                continue;
            }
            targets.add(target((TypeElement) ((DeclaredType) type).asElement()));
        }
        for (AnnotationValue value : list(values.get("targets"))) {
            String target = (String) value.getValue();
            TypeElement type = byBinaryName(target);
            if (type == null) {
                error(hookClass, Messages.targetNotFound(name, target));
                ok = false;
                continue;
            }
            targets.add(target(type));
        }
        int before = errors;
        HookDecls.Gate gate = gate(hookClass, name, (TypeMirror) values.get("gate").getValue());
        ok &= errors == before;

        List<Handler<TypeMirror>> handlers = new ArrayList<>();
        for (ExecutableElement method : ElementFilter.methodsIn(hookClass.getEnclosedElements())) {
            Handler<TypeMirror> handler = handler(hookClass, method);
            if (handler != null) {
                handlers.add(handler);
            } else if (HANDLERS.stream().anyMatch(h -> mirror(method, h) != null)) {
                ok = false;
            }
        }
        if (!ok) {
            return null;
        }
        String simple = hookClass.getSimpleName().toString();
        String binary = processingEnv.getElementUtils().getBinaryName(hookClass).toString();
        return new HookClass<>(hookClass, name, binary, simple, name, gate, targets, handlers);
    }

    /** Null for the default gate, and after an error. */
    private HookDecls.Gate gate(TypeElement hookClass, String name, TypeMirror gate) {
        if (gate.getKind() != TypeKind.DECLARED) {
            error(hookClass, name + ": the gate class " + gate + " cannot be resolved");
            return null;
        }
        TypeElement type = (TypeElement) ((DeclaredType) gate).asElement();
        String gateName = type.getQualifiedName().toString();
        if (gateName.equals(Names.HOOK_GATE)) {
            return null;
        }
        TypeMirror hookGate = processingEnv.getElementUtils().getTypeElement(Names.HOOK_GATE).asType();
        VariableElement instance = ElementFilter.fieldsIn(type.getEnclosedElements()).stream()
                .filter(f -> f.getSimpleName().contentEquals("INSTANCE")).findFirst().orElse(null);
        boolean publicChain = true;
        for (Element e = type; e instanceof TypeElement t; e = e.getEnclosingElement()) {
            publicChain &= t.getModifiers().contains(Modifier.PUBLIC);
        }
        if (instance == null || !instance.getModifiers().containsAll(Set.of(Modifier.PUBLIC, Modifier.STATIC,
                Modifier.FINAL)) || !processingEnv.getTypeUtils().isAssignable(instance.asType(), hookGate)
                || !publicChain) {
            error(hookClass, name + ": the gate " + gateName + " must be a public HookGate with a public static final"
                    + " INSTANCE of a HookGate type (or a Kotlin object)");
            return null;
        }
        return new HookDecls.Gate(gateName, processingEnv.getElementUtils().getBinaryName(type).toString());
    }

    /** The handler declared by {@code method}, or null when it is none or after an error. */
    private Handler<TypeMirror> handler(TypeElement hookClass, ExecutableElement method) {
        List<String> kinds = HANDLERS.stream().filter(h -> mirror(method, h) != null).toList();
        String label = hookClass.getSimpleName() + "." + method.getSimpleName();
        if (kinds.isEmpty()) {
            return null;
        }
        if (kinds.size() > 1) {
            error(method, Messages.severalKinds(label));
            return null;
        }
        boolean ok = true;
        if (!method.getModifiers().containsAll(Set.of(Modifier.PUBLIC, Modifier.STATIC))) {
            error(method, label + ": a handler must be public static");
            ok = false;
        }
        if (!method.getTypeParameters().isEmpty()) {
            error(method, label + ": a handler cannot have type parameters");
            ok = false;
        }
        String annotation = kinds.get(0);
        Kind kind = annotation.equals(Names.INJECT) ? Kind.INJECT
                : annotation.equals(Names.MODIFY_RETURN_VALUE) ? Kind.MODIFY_RETURN_VALUE : Kind.MODIFY_VARIABLE;
        Map<String, AnnotationValue> values = values(mirror(method, annotation));
        List<String> targets = list(values.get("method")).stream().map(v -> (String) v.getValue()).toList();
        AnnotationValue atValue = values.get("at");
        List<AnnotationValue> ats = atValue.getValue() instanceof List<?> ? list(atValue) : List.of(atValue);
        List<String> at = ats.stream()
                .map(v -> (String) values((AnnotationMirror) v.getValue()).get("value").getValue()).toList();
        boolean cancellable = bool(values.get("cancellable"));
        boolean always = bool(values.get("always"));
        boolean argsOnly = kind != Kind.MODIFY_VARIABLE || bool(values.get("argsOnly"));
        int index = values.containsKey("index") ? (Integer) values.get("index").getValue() : -1;
        int ordinal = values.containsKey("ordinal") ? (Integer) values.get("ordinal").getValue() : -1;

        List<Param<TypeMirror>> params = new ArrayList<>();
        for (VariableElement param : method.getParameters()) {
            AnnotationMirror share = mirror(param, Names.SHARE);
            params.add(new Param<>(param.getSimpleName().toString(), param.asType(),
                    mirror(param, Names.SELF) != null,
                    share == null ? null : (String) values(share).get("value").getValue(),
                    mirror(param, Names.THROWN) != null));
        }
        if (!ok) {
            return null;
        }
        return new Handler<>(method, method.getSimpleName().toString(), kind, targets, at, cancellable, always,
                argsOnly, index, ordinal, params, method.getReturnType());
    }

    private boolean checkMixin(TypeElement hookClass, String name) {
        boolean ok = true;
        Deque<Element> todo = new ArrayDeque<>(List.of(hookClass));
        while (!todo.isEmpty()) {
            Element e = todo.pop();
            for (AnnotationMirror m : e.getAnnotationMirrors()) {
                String annotation = ((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().toString();
                if (Messages.isMixin(annotation)) {
                    String member = e.getKind() == ElementKind.PARAMETER
                            ? e.getEnclosingElement().getSimpleName() + " parameter " + e.getSimpleName()
                            : e.getSimpleName().toString();
                    String where = e == hookClass ? name : hookClass.getSimpleName() + "." + member;
                    error(e, Messages.unsupportedMixin(where, annotation));
                    ok = false;
                }
            }
            if (e == hookClass) {
                ElementFilter.methodsIn(hookClass.getEnclosedElements()).forEach(todo::add);
                ElementFilter.fieldsIn(hookClass.getEnclosedElements()).forEach(todo::add);
            } else if (e instanceof ExecutableElement method) {
                todo.addAll(method.getParameters());
            }
        }
        return ok;
    }

    /** Declared methods and constructors in order, plus the bridges javac adds for them (class files hide those). */
    private TargetClass<TypeMirror> target(TypeElement type) {
        List<Member<TypeMirror>> members = new ArrayList<>();
        Set<String> descriptors = new HashSet<>();
        Function<ExecutableElement, Member<TypeMirror>> member = e -> {
            Set<Modifier> modifiers = e.getModifiers();
            String name = e.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : e.getSimpleName().toString();
            List<TypeMirror> params = e.getParameters().stream().map(VariableElement::asType)
                    .collect(Collectors.toList());
            return new Member<>(name, params, e.getReturnType(), modifiers.contains(Modifier.STATIC),
                    !modifiers.contains(Modifier.ABSTRACT) && !modifiers.contains(Modifier.NATIVE), false);
        };
        List<ExecutableElement> declared = new ArrayList<>();
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD || e.getKind() == ElementKind.CONSTRUCTOR) {
                ExecutableElement executable = (ExecutableElement) e;
                declared.add(executable);
                Member<TypeMirror> m = member.apply(executable);
                members.add(m);
                descriptors.add(m.name() + descriptor(m.params(), m.returnType()));
            }
        }
        List<TypeElement> supertypes = supertypes(type);
        for (ExecutableElement method : ElementFilter.methodsIn(declared)) {
            if (method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            for (TypeElement supertype : supertypes) {
                for (ExecutableElement overridden : ElementFilter.methodsIn(supertype.getEnclosedElements())) {
                    if (!overridden.getSimpleName().equals(method.getSimpleName())
                            || !processingEnv.getElementUtils().overrides(method, overridden, type)) {
                        continue;
                    }
                    List<TypeMirror> params = overridden.getParameters().stream()
                            .map(p -> processingEnv.getTypeUtils().erasure(p.asType())).collect(Collectors.toList());
                    TypeMirror returns = processingEnv.getTypeUtils().erasure(overridden.getReturnType());
                    String name = method.getSimpleName().toString();
                    if (descriptors.add(name + descriptor(params, returns))) {
                        members.add(new Member<>(name, params, returns, false, true, true));
                    }
                }
            }
        }
        String binary = processingEnv.getElementUtils().getBinaryName(type).toString();
        return new TargetClass<>(binary, type.asType(), members);
    }

    private String descriptor(List<TypeMirror> params, TypeMirror returns) {
        return "(" + params.stream().map(types::descriptor).collect(Collectors.joining()) + ")"
                + types.descriptor(returns);
    }

    private List<TypeElement> supertypes(TypeElement type) {
        List<TypeElement> all = new ArrayList<>();
        Deque<TypeMirror> todo = new ArrayDeque<>(processingEnv.getTypeUtils().directSupertypes(type.asType()));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            TypeMirror next = todo.pop();
            if (next.getKind() != TypeKind.DECLARED) {
                continue;
            }
            TypeElement element = (TypeElement) ((DeclaredType) next).asElement();
            if (seen.add(element.getQualifiedName().toString())) {
                all.add(element);
                todo.addAll(processingEnv.getTypeUtils().directSupertypes(next));
            }
        }
        return all;
    }

    /** A class by binary name ({@code a.b.Outer$Inner}) or canonical name. */
    private TypeElement byBinaryName(String name) {
        for (String candidate : List.of(name, name.replace('$', '.'))) {
            TypeElement type = processingEnv.getElementUtils().getTypeElement(candidate);
            if (type != null && (processingEnv.getElementUtils().getBinaryName(type).contentEquals(name)
                    || type.getQualifiedName().contentEquals(name))) {
                return type;
            }
        }
        return null;
    }

    // ---- annotation helpers ----

    private Set<? extends Element> annotated(RoundEnvironment round, String annotation) {
        TypeElement type = processingEnv.getElementUtils().getTypeElement(annotation);
        return type == null ? Set.of() : round.getElementsAnnotatedWith(type);
    }

    private static AnnotationMirror mirror(Element element, String annotation) {
        for (AnnotationMirror m : element.getAnnotationMirrors()) {
            if (((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation)) {
                return m;
            }
        }
        return null;
    }

    private Map<String, AnnotationValue> values(AnnotationMirror mirror) {
        return processingEnv.getElementUtils().getElementValuesWithDefaults(mirror).entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().getSimpleName().toString(), Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationValue> list(AnnotationValue value) {
        return value == null ? List.of() : (List<AnnotationValue>) value.getValue();
    }

    private static boolean bool(AnnotationValue value) {
        return value != null && (Boolean) value.getValue();
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private void error(Element element, String message) {
        errors++;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
