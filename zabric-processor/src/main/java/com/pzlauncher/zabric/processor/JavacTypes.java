package com.pzlauncher.zabric.processor;

import com.pzlauncher.zabric.processor.glue.TypeSystem;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** {@link TypeSystem} on javac's model. */
final class JavacTypes implements TypeSystem<TypeMirror> {
    private final Types types;
    private final Elements elements;

    JavacTypes(ProcessingEnvironment env) {
        this.types = env.getTypeUtils();
        this.elements = env.getElementUtils();
    }

    @Override
    public String erasedName(TypeMirror type) {
        TypeKind kind = type.getKind();
        if (kind.isPrimitive() || kind == TypeKind.VOID) {
            return kind.name().toLowerCase(Locale.ROOT);
        }
        return switch (kind) {
            case ARRAY -> erasedName(((ArrayType) type).getComponentType()) + "[]";
            case DECLARED -> ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
            case TYPEVAR, WILDCARD, INTERSECTION -> erasedName(types.erasure(type));
            default -> type.toString();
        };
    }

    @Override
    public String descriptor(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case CHAR -> "C";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case ARRAY -> "[" + descriptor(((ArrayType) type).getComponentType());
            case DECLARED -> "L" + elements.getBinaryName((TypeElement) ((DeclaredType) type).asElement()).toString()
                    .replace('.', '/') + ";";
            case TYPEVAR, WILDCARD, INTERSECTION -> descriptor(types.erasure(type));
            default -> type.toString();
        };
    }

    @Override
    public String source(TypeMirror type) {
        return switch (type.getKind()) {
            case ARRAY -> source(((ArrayType) type).getComponentType()) + "[]";
            case DECLARED -> {
                List<? extends TypeMirror> args = ((DeclaredType) type).getTypeArguments();
                String name = erasedName(type);
                yield args.isEmpty() ? name
                        : name + "<" + args.stream().map(this::source).collect(Collectors.joining(", ")) + ">";
            }
            case WILDCARD -> {
                WildcardType wildcard = (WildcardType) type;
                if (wildcard.getExtendsBound() != null) {
                    yield "? extends " + source(wildcard.getExtendsBound());
                }
                yield wildcard.getSuperBound() != null ? "? super " + source(wildcard.getSuperBound()) : "?";
            }
            // the glue cannot name a type variable
            case TYPEVAR, INTERSECTION -> source(types.erasure(type));
            default -> erasedName(type);
        };
    }

    @Override
    public List<TypeMirror> typeArguments(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED ? List.copyOf(((DeclaredType) type).getTypeArguments()) : List.of();
    }

    @Override
    public TypeMirror component(TypeMirror type) {
        return type.getKind() == TypeKind.ARRAY ? ((ArrayType) type).getComponentType() : null;
    }

    @Override
    public boolean isSubtype(TypeMirror sub, TypeMirror sup) {
        TypeKind a = sub.getKind();
        TypeKind b = sup.getKind();
        if (a.isPrimitive() || b.isPrimitive() || a == TypeKind.VOID || b == TypeKind.VOID) {
            // javac's subtyping widens primitives; the JVM does not
            return a == b;
        }
        return types.isSubtype(types.erasure(sub), types.erasure(sup));
    }

    @Override
    public String accessProblem(TypeMirror type, String pkg) {
        TypeMirror erased = types.erasure(type);
        while (erased.getKind() == TypeKind.ARRAY) {
            erased = ((ArrayType) erased).getComponentType();
        }
        if (erased.getKind() != TypeKind.DECLARED) {
            return null;
        }
        for (Element e = ((DeclaredType) erased).asElement(); e instanceof TypeElement t; e = e.getEnclosingElement()) {
            if (t.getModifiers().contains(Modifier.PUBLIC)) {
                continue;
            }
            PackageElement own = elements.getPackageOf(t);
            if (t.getModifiers().contains(Modifier.PRIVATE) || !own.getQualifiedName().contentEquals(pkg)) {
                return t.getQualifiedName() + " is not public";
            }
        }
        return null;
    }
}
