package com.pzlauncher.zabric.processor;

import com.pzlauncher.zabric.processor.glue.HookDecls;
import com.pzlauncher.zabric.processor.glue.HookDecls.Handler;
import com.pzlauncher.zabric.processor.glue.HookDecls.HookClass;
import com.pzlauncher.zabric.processor.glue.HookDecls.Kind;
import com.pzlauncher.zabric.processor.glue.HookDecls.Member;
import com.pzlauncher.zabric.processor.glue.HookDecls.Param;
import com.pzlauncher.zabric.processor.glue.HookDecls.TargetClass;
import com.pzlauncher.zabric.processor.glue.Messages;
import com.pzlauncher.zabric.processor.glue.Names;

import javax.annotation.processing.ProcessingEnvironment;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reads {@code @Hooks} classes into Zabric's {@link HookDecls}, adding the Java-only rules. */
final class JavacHookReader {
    static final List<String> HANDLERS = List.of(Names.INJECT, Names.MODIFY_RETURN_VALUE, Names.MODIFY_VARIABLE);
    static final List<String> PARAMS = List.of(Names.SELF, Names.SHARE, Names.THROWN);

    private final ProcessingEnvironment env;
    private final JavacTypes types;
    private final BiConsumer<Element, String> reporter;
    private int errors;

    JavacHookReader(ProcessingEnvironment env, JavacTypes types, BiConsumer<Element, String> reporter) {
        this.env = env;
        this.types = types;
        this.reporter = reporter;
    }

    /** Null after an error. */
    HookClass<TypeMirror> read(TypeElement hookClass) {
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
        String binary = env.getElementUtils().getBinaryName(hookClass).toString();
        return new HookClass<>(hookClass, name, binary, simple, name, gate, targets, handlers);
    }

    /** Null for the default gate, and after an error. */
    private HookDecls.Gate gate(TypeElement hookClass, String name, TypeMirror gate) {
        if (gate.getKind() != TypeKind.DECLARED) {
            error(hookClass, Messages.gateNotFound(name, gate.toString()));
            return null;
        }
        TypeElement type = (TypeElement) ((DeclaredType) gate).asElement();
        String gateName = type.getQualifiedName().toString();
        if (gateName.equals(Names.HOOK_GATE)) {
            return null;
        }
        TypeMirror hookGate = env.getElementUtils().getTypeElement(Names.HOOK_GATE).asType();
        VariableElement instance = ElementFilter.fieldsIn(type.getEnclosedElements()).stream()
                .filter(f -> f.getSimpleName().contentEquals("INSTANCE")).findFirst().orElse(null);
        boolean publicChain = true;
        for (Element e = type; e instanceof TypeElement t; e = e.getEnclosingElement()) {
            publicChain &= t.getModifiers().contains(Modifier.PUBLIC);
        }
        if (instance == null || !instance.getModifiers().containsAll(Set.of(Modifier.PUBLIC, Modifier.STATIC,
                Modifier.FINAL)) || !env.getTypeUtils().isAssignable(instance.asType(), hookGate)
                || !publicChain) {
            error(hookClass, Messages.badGate(name, gateName));
            return null;
        }
        return new HookDecls.Gate(gateName, env.getElementUtils().getBinaryName(type).toString());
    }

    /** Null when {@code method} is no handler, or after an error. */
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
            error(method, Messages.handlerTypeParameters(label));
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
                todo.addAll(ElementFilter.methodsIn(hookClass.getEnclosedElements()));
                todo.addAll(ElementFilter.fieldsIn(hookClass.getEnclosedElements()));
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
                            || !env.getElementUtils().overrides(method, overridden, type)) {
                        continue;
                    }
                    List<TypeMirror> params = overridden.getParameters().stream()
                            .map(p -> env.getTypeUtils().erasure(p.asType())).collect(Collectors.toList());
                    TypeMirror returns = env.getTypeUtils().erasure(overridden.getReturnType());
                    String name = method.getSimpleName().toString();
                    if (descriptors.add(name + descriptor(params, returns))) {
                        members.add(new Member<>(name, params, returns, false, true, true));
                    }
                }
            }
        }
        String binary = env.getElementUtils().getBinaryName(type).toString();
        return new TargetClass<>(binary, type.asType(), members);
    }

    private String descriptor(List<TypeMirror> params, TypeMirror returns) {
        return "(" + params.stream().map(types::descriptor).collect(Collectors.joining()) + ")"
                + types.descriptor(returns);
    }

    private List<TypeElement> supertypes(TypeElement type) {
        List<TypeElement> all = new ArrayList<>();
        Deque<TypeMirror> todo = new ArrayDeque<>(env.getTypeUtils().directSupertypes(type.asType()));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            TypeMirror next = todo.pop();
            if (next.getKind() != TypeKind.DECLARED) {
                continue;
            }
            TypeElement element = (TypeElement) ((DeclaredType) next).asElement();
            if (seen.add(element.getQualifiedName().toString())) {
                all.add(element);
                todo.addAll(env.getTypeUtils().directSupertypes(next));
            }
        }
        return all;
    }

    /** A class by binary name ({@code a.b.Outer$Inner}) or canonical name. */
    private TypeElement byBinaryName(String name) {
        for (String candidate : List.of(name, name.replace('$', '.'))) {
            TypeElement type = env.getElementUtils().getTypeElement(candidate);
            if (type != null && (env.getElementUtils().getBinaryName(type).contentEquals(name)
                    || type.getQualifiedName().contentEquals(name))) {
                return type;
            }
        }
        return null;
    }

    static AnnotationMirror mirror(Element element, String annotation) {
        for (AnnotationMirror m : element.getAnnotationMirrors()) {
            if (((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation)) {
                return m;
            }
        }
        return null;
    }

    private Map<String, AnnotationValue> values(AnnotationMirror mirror) {
        return env.getElementUtils().getElementValuesWithDefaults(mirror).entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().getSimpleName().toString(), Map.Entry::getValue));
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationValue> list(AnnotationValue value) {
        return value == null ? List.of() : (List<AnnotationValue>) value.getValue();
    }

    private static boolean bool(AnnotationValue value) {
        return value != null && (Boolean) value.getValue();
    }

    private void error(Element element, String message) {
        errors++;
        reporter.accept(element, message);
    }
}
