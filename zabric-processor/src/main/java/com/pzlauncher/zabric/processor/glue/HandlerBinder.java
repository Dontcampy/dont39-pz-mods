package com.pzlauncher.zabric.processor.glue;

import com.pzlauncher.zabric.processor.glue.GlueModel.At;
import com.pzlauncher.zabric.processor.glue.GlueModel.Value;
import com.pzlauncher.zabric.processor.glue.HookDecls.Handler;
import com.pzlauncher.zabric.processor.glue.HookDecls.HookClass;
import com.pzlauncher.zabric.processor.glue.HookDecls.Kind;
import com.pzlauncher.zabric.processor.glue.HookDecls.Member;
import com.pzlauncher.zabric.processor.glue.HookDecls.Param;
import com.pzlauncher.zabric.processor.glue.HookDecls.TargetClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Checks a handler's own shape, finds its target method, and binds its parameters to what the glue passes. */
final class HandlerBinder<T> {
    private static final Pattern SHARE_KEY = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern METHOD = Pattern.compile("([^(]+)(\\(.*)?");

    private final Analysis<T> analysis;

    HandlerBinder(Analysis<T> analysis) {
        this.analysis = analysis;
    }

    /** What the handler binds to; {@code named}: the handler's types the glue writes out. */
    record Resolved(String label, Object node, HookClass<?> hookClass, Kind kind, boolean always, At at,
                    boolean cancellable, String name, List<Value> values, String returnable, int modifiedArg,
                    Map<String, String> shares, List<Object> named) {
    }

    record Shape(At at, String method, String descriptor) {
    }

    /** What does not depend on the target class; null after reporting. */
    Shape shape(HookClass<T> hookClass, Handler<T> handler) {
        String label = label(hookClass, handler);
        Object node = handler.node();
        boolean ok = true;
        if (handler.method().size() != 1) {
            error(node, label + ": method must name exactly one target method (found " + handler.method().size()
                    + ")");
            ok = false;
        }
        At at = null;
        if (handler.at().size() != 1) {
            error(node, label + ": at must be exactly one @At (found " + handler.at().size() + ")");
            ok = false;
        } else {
            at = at(label, node, handler.at().get(0));
            ok &= at != null;
        }
        if (at != null) {
            if (handler.kind() == Kind.MODIFY_RETURN_VALUE && at != At.RETURN) {
                error(node, label + ": @ModifyReturnValue runs at RETURN only, not " + at);
                ok = false;
            }
            if (handler.kind() == Kind.MODIFY_VARIABLE && at != At.HEAD) {
                error(node, label + ": @ModifyVariable changes an argument at HEAD only, not " + at
                        + " (Mixin's target locals are not supported)");
                ok = false;
            }
            if (handler.cancellable() && at == At.FINALLY) {
                error(node, label + ": a FINALLY handler cannot be cancellable");
                ok = false;
            }
        }
        if (handler.kind() == Kind.MODIFY_VARIABLE) {
            if (!handler.argsOnly()) {
                error(node, label + ": @ModifyVariable needs argsOnly = true: only the target's arguments can be"
                        + " modified (Mixin's target locals are not supported)");
                ok = false;
            }
            if (handler.index() >= 0 && handler.ordinal() >= 0) {
                error(node, label + ": give index or ordinal, not both");
                ok = false;
            }
        }
        for (Param<T> param : handler.params()) {
            int marks = (param.self() ? 1 : 0) + (param.share() != null ? 1 : 0) + (param.thrown() ? 1 : 0);
            if (marks > 1) {
                error(node, label + ": parameter " + param.name() + " has more than one of @Self, @Share and"
                        + " @Thrown");
                ok = false;
            }
            if (param.thrown() && (handler.kind() != Kind.INJECT || at != At.FINALLY)) {
                error(node, label + ": parameter " + param.name() + ": @Thrown is only for @Inject handlers at"
                        + " FINALLY");
                ok = false;
            }
            if (param.share() != null && !SHARE_KEY.matcher(param.share()).matches()) {
                error(node, label + ": parameter " + param.name() + ": the @Share key '" + param.share()
                        + "' must be letters, digits and underscores");
                ok = false;
            }
        }
        if (!ok || handler.method().size() != 1) {
            return null;
        }
        String method = handler.method().get(0);
        var matcher = METHOD.matcher(method);
        if (!matcher.matches()) {
            error(node, label + ": method '" + method + "' is not \"name\" or \"name(descriptor)\"");
            return null;
        }
        String name = matcher.group(1);
        if (name.equals("<clinit>")) {
            error(node, label + ": static initializers cannot be hooked");
            return null;
        }
        if (!name.equals("<init>") && !HookAnalyzer.IDENTIFIER.matcher(name).matches()) {
            error(node, label + ": method '" + method + "' is not \"name\" or \"name(descriptor)\"");
            return null;
        }
        return new Shape(at, name, matcher.group(2));
    }

    private At at(String label, Object node, String value) {
        try {
            return At.valueOf(value);
        } catch (IllegalArgumentException e) {
            error(node, label + ": @At(\"" + value + "\") is not supported; Zabric hooks run at HEAD, RETURN and"
                    + " FINALLY only (Mixin's INVOKE, FIELD, NEW, TAIL, ... are not supported)");
            return null;
        }
    }

    /** Index in {@code target.members()}, or -1 after reporting. */
    int member(HookClass<T> hookClass, Handler<T> handler, Shape shape, TargetClass<T> target) {
        String label = label(hookClass, handler);
        List<Integer> named = new ArrayList<>();
        for (int i = 0; i < target.members().size(); i++) {
            Member<T> m = target.members().get(i);
            if (!m.bridge() && m.name().equals(shape.method())) {
                named.add(i);
            }
        }
        String what = shape.method().equals("<init>") ? "constructor" : "method " + shape.method();
        if (named.isEmpty()) {
            error(handler.node(), label + ": " + target.binaryName() + " declares no " + what);
            return -1;
        }
        int found = -1;
        if (shape.descriptor() != null) {
            for (int i : named) {
                if (analysis.descriptor(target.members().get(i)).equals(shape.descriptor())) {
                    found = i;
                }
            }
            if (found < 0) {
                error(handler.node(), label + ": " + target.binaryName() + " declares no " + shape.method()
                        + shape.descriptor() + "; it has " + overloads(target, named));
                return -1;
            }
        } else if (named.size() > 1) {
            error(handler.node(), label + ": " + target.binaryName() + " declares " + named.size() + " "
                    + (shape.method().equals("<init>") ? "constructors" : "methods named " + shape.method()) + ": "
                    + overloads(target, named) + "; name one with \"" + shape.method() + "(descriptor)\"");
            return -1;
        } else {
            found = named.get(0);
        }
        Member<T> member = target.members().get(found);
        String site = target.binaryName() + "." + shape.method() + analysis.descriptor(member);
        if (!member.hasBody()) {
            error(handler.node(), label + ": " + site + " is abstract or native: it has no body to hook");
            return -1;
        }
        if (member.name().equals("<init>")) {
            if (handler.kind() == Kind.INJECT && shape.at() != At.RETURN) {
                error(handler.node(), label + ": constructors can be hooked at RETURN only, not " + shape.at()
                        + " (" + site + ")");
                return -1;
            }
            if (handler.kind() == Kind.MODIFY_VARIABLE) {
                error(handler.node(), label + ": constructors can be hooked at RETURN only, so @ModifyVariable"
                        + " cannot change their arguments (" + site + ")");
                return -1;
            }
        }
        if (handler.kind() == Kind.MODIFY_RETURN_VALUE && analysis.isVoid(member.returnType())) {
            error(handler.node(), label + ": " + site + " returns nothing; @ModifyReturnValue needs a return"
                    + " value");
            return -1;
        }
        return found;
    }

    private String overloads(TargetClass<T> target, List<Integer> indexes) {
        return indexes.stream().map(i -> {
            Member<T> m = target.members().get(i);
            return m.name() + analysis.descriptor(m);
        }).collect(Collectors.joining(", "));
    }

    /** Null after reporting. */
    Resolved bind(HookClass<T> hookClass, Handler<T> handler, Shape shape, TargetClass<T> target, Member<T> member) {
        String label = label(hookClass, handler);
        HandlerBinding<T> binding = new HandlerBinding<>(analysis, label, handler, target, member);
        if (!binding.bind()) {
            return null;
        }
        return new Resolved(label, handler.node(), hookClass, handler.kind(), handler.always(), shape.at(),
                handler.cancellable(), handler.name(), binding.values, binding.returnable, binding.modifiedArg,
                binding.shares, binding.named);
    }

    private static String label(HookClass<?> hookClass, Handler<?> handler) {
        return hookClass.simpleName() + "." + handler.name();
    }

    private void error(Object node, String message) {
        analysis.error(node, message);
    }
}
