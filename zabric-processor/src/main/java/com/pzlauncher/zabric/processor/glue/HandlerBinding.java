package com.pzlauncher.zabric.processor.glue;

import com.pzlauncher.zabric.processor.glue.GlueModel.Value;
import com.pzlauncher.zabric.processor.glue.HookDecls.Handler;
import com.pzlauncher.zabric.processor.glue.HookDecls.Kind;
import com.pzlauncher.zabric.processor.glue.HookDecls.Member;
import com.pzlauncher.zabric.processor.glue.HookDecls.Param;
import com.pzlauncher.zabric.processor.glue.HookDecls.TargetClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One handler's parameters, left to right, against one target method: what the glue passes for each. */
final class HandlerBinding<T> {
    private static final String INJECT_SIGNATURE = "void h([@Self T self,] [target parameters...,]"
            + " CallbackInfo ci [, @Thrown Throwable t] [, @Share(\"key\") LocalRef<S> s...]), with"
            + " CallbackInfoReturnable<R> for a non-void target";
    private static final String MODIFY_SIGNATURE = " h(%1$s original [, @Self T self] [, target parameters...]"
            + " [, @Share(\"key\") LocalRef<S> s...])";

    private final Analysis<T> analysis;
    private final TypeSystem<T> types;
    private final String label;
    private final Handler<T> handler;
    private final TargetClass<T> target;
    private final Member<T> member;
    private final Object node;
    private final String site;
    private final List<Param<T>> params;
    final List<Value> values = new ArrayList<>();
    final List<Object> named = new ArrayList<>();
    final Map<String, String> shares = new LinkedHashMap<>();
    String returnable;
    int modifiedArg = -1;
    private boolean ok = true;
    /** The next parameter. */
    private int i;

    HandlerBinding(Analysis<T> analysis, String label, Handler<T> handler, TargetClass<T> target, Member<T> member) {
        this.analysis = analysis;
        this.types = analysis.types;
        this.label = label;
        this.handler = handler;
        this.target = target;
        this.member = member;
        node = handler.node();
        site = target.binaryName() + "." + member.name() + analysis.descriptor(member);
        params = handler.params();
    }

    /** False after reporting. */
    boolean bind() {
        boolean complete = handler.kind() == Kind.INJECT ? bindInject() : bindModify();
        return complete && ok;
    }

    /** False when a parameter is out of place; {@link #ok} also tells the other errors. */
    private boolean bindInject() {
        selfAndArgs();
        if (!callbackInfo()) {
            return false;
        }
        if (!shares(INJECT_SIGNATURE)) {
            return false;
        }
        if (!analysis.isVoid(handler.returnType())) {
            error(node, label + ": an @Inject handler returns void");
            ok = false;
        }
        return true;
    }

    /** As {@link #bindInject}, for the handlers that take and return the original value. */
    private boolean bindModify() {
        String signature = handler.kind() == Kind.MODIFY_RETURN_VALUE ? "R" + MODIFY_SIGNATURE.formatted("R")
                : "P" + MODIFY_SIGNATURE.formatted("P");
        if (params.isEmpty() || marked(params.get(0))) {
            error(node, label + ": the first parameter is the original value; the signature is " + signature);
            return false;
        }
        values.add(Value.of(Value.Kind.ORIGINAL));
        i = 1;
        selfAndArgs();
        if (!shares(signature)) {
            return false;
        }
        if (handler.kind() == Kind.MODIFY_RETURN_VALUE && ok) {
            returnValue();
        }
        if (handler.kind() == Kind.MODIFY_VARIABLE && ok) {
            variable();
        }
        return true;
    }

    private void selfAndArgs() {
        if (i < params.size() && params.get(i).self()) {
            Param<T> self = params.get(i);
            if (member.isStatic()) {
                error(node, label + ": parameter " + self.name() + ": @Self on the static " + site);
                ok = false;
            } else if (!types.isSubtype(target.type(), self.type())) {
                error(node, label + ": parameter " + self.name() + ": @Self is " + types.erasedName(self.type())
                        + ", which " + target.binaryName() + " is not");
                ok = false;
            }
            values.add(Value.of(Value.Kind.SELF));
            i++;
        }
        int first = i;
        while (i < params.size() && !marked(params.get(i)) && !isCallbackInfo(params.get(i).type())) {
            i++;
        }
        int taken = i - first;
        int count = member.params().size();
        if (taken != 0 && taken != count) {
            error(node, label + ": takes " + taken + " of the " + count + " parameters of " + site
                    + "; take all or none");
            ok = false;
            return;
        }
        for (int k = 0; k < taken; k++) {
            Param<T> param = params.get(first + k);
            T from = member.params().get(k);
            if (!types.isSubtype(from, param.type())) {
                error(node, label + ": parameter " + param.name() + " is " + types.erasedName(param.type())
                        + ", but parameter " + k + " of " + site + " is " + types.erasedName(from));
                ok = false;
            }
            values.add(new Value(Value.Kind.ARG, k, null));
        }
    }

    /** The CallbackInfo and an optional @Thrown; false when it is missing. */
    private boolean callbackInfo() {
        if (i >= params.size() || marked(params.get(i)) || !isCallbackInfo(params.get(i).type())) {
            error(node, label + ": needs a CallbackInfo parameter after the target's parameters; the signature"
                    + " is " + INJECT_SIGNATURE);
            return false;
        }
        Param<T> ci = params.get(i);
        String ciType = types.erasedName(ci.type());
        T targetReturn = member.returnType();
        if (analysis.isVoid(targetReturn)) {
            if (!ciType.equals(Names.CALLBACK_INFO)) {
                error(node, label + ": parameter " + ci.name() + ": " + site + " returns nothing, so it takes a"
                        + " CallbackInfo, not " + ciType);
                ok = false;
            }
        } else {
            String boxed = box(types.erasedName(targetReturn));
            List<T> arguments = types.typeArguments(ci.type());
            T argument = arguments.size() == 1 ? arguments.get(0) : null;
            if (!ciType.equals(Names.CALLBACK_INFO_RETURNABLE) || argument == null
                    || types.source(argument).startsWith("?")
                    || !types.erasedName(argument).equals(boxed)) {
                error(node, label + ": parameter " + ci.name() + ": " + site + " returns "
                        + types.erasedName(targetReturn) + ", so it takes a CallbackInfoReturnable<" + boxed
                        + ">, not " + types.source(ci.type()));
                ok = false;
            } else {
                returnable = types.source(argument);
                named.add(argument);
            }
        }
        values.add(Value.of(Value.Kind.CALLBACK));
        i++;
        if (i < params.size() && params.get(i).thrown()) {
            Param<T> thrown = params.get(i);
            if (!types.erasedName(thrown.type()).equals(Names.THROWABLE)) {
                error(node, label + ": parameter " + thrown.name() + ": @Thrown must be a Throwable, not "
                        + types.erasedName(thrown.type()));
                ok = false;
            }
            values.add(Value.of(Value.Kind.THROWN));
            i++;
        }
        return true;
    }

    /** The remaining parameters, all @Share; false when one is not. */
    private boolean shares(String signature) {
        for (; i < params.size(); i++) {
            Param<T> param = params.get(i);
            if (param.share() == null) {
                error(node, label + ": parameter " + param.name() + " is out of place; the signature is "
                        + signature);
                return false;
            }
            List<T> arguments = types.typeArguments(param.type());
            if (!types.erasedName(param.type()).equals(Names.LOCAL_REF) || arguments.size() != 1) {
                error(node, label + ": parameter " + param.name() + ": @Share must be a LocalRef<S>, not "
                        + types.source(param.type()));
                ok = false;
                continue;
            }
            if (shares.put(param.share(), types.source(arguments.get(0))) != null) {
                error(node, label + ": @Share(\"" + param.share() + "\") is taken twice");
                ok = false;
            }
            named.add(arguments.get(0));
            values.add(new Value(Value.Kind.SHARE, -1, param.share()));
        }
        return true;
    }

    private void returnValue() {
        Param<T> original = params.get(0);
        T targetReturn = member.returnType();
        if (!types.isSubtype(targetReturn, original.type())) {
            error(node, label + ": parameter " + original.name() + " is " + types.erasedName(original.type())
                    + ", but " + site + " returns " + types.erasedName(targetReturn));
            ok = false;
        }
        if (!types.isSubtype(handler.returnType(), targetReturn)) {
            error(node, label + ": returns " + types.erasedName(handler.returnType()) + ", but " + site
                    + " returns " + types.erasedName(targetReturn));
            ok = false;
        }
    }

    private void variable() {
        modifiedArg = modifiedArg();
        if (modifiedArg < 0) {
            ok = false;
            return;
        }
        T arg = member.params().get(modifiedArg);
        if (!types.isSubtype(handler.returnType(), arg)) {
            error(node, label + ": returns " + types.erasedName(handler.returnType()) + ", but parameter "
                    + modifiedArg + " of " + site + " is " + types.erasedName(arg));
            ok = false;
        }
    }

    /** As Mixin: {@code index} is a local variable slot, else {@code ordinal} among the arguments of the type. */
    private int modifiedArg() {
        String type = types.erasedName(params.get(0).type());
        List<T> args = member.params();
        if (handler.index() >= 0) {
            int slot = member.isStatic() ? 0 : 1;
            for (int k = 0; k < args.size(); k++) {
                if (slot == handler.index()) {
                    if (!types.erasedName(args.get(k)).equals(type)) {
                        error(node, label + ": slot " + handler.index() + " of " + site + " holds parameter "
                                + k + " of type " + types.erasedName(args.get(k)) + ", not " + type);
                        return -1;
                    }
                    return k;
                }
                String name = types.erasedName(args.get(k));
                slot += name.equals("long") || name.equals("double") ? 2 : 1;
            }
            error(node, label + ": index " + handler.index() + " is not the local variable slot of a parameter"
                    + " of " + site + (member.isStatic() ? "" : " (slot 0 is this)"));
            return -1;
        }
        List<Integer> ofType = new ArrayList<>();
        for (int k = 0; k < args.size(); k++) {
            if (types.erasedName(args.get(k)).equals(type)) {
                ofType.add(k);
            }
        }
        if (handler.ordinal() >= 0) {
            if (handler.ordinal() >= ofType.size()) {
                error(node, label + ": ordinal " + handler.ordinal() + ", but " + site + " has " + ofType.size()
                        + " parameters of type " + type);
                return -1;
            }
            return ofType.get(handler.ordinal());
        }
        if (ofType.size() != 1) {
            error(node, label + ": " + site + " has " + ofType.size() + " parameters of type " + type
                    + (ofType.isEmpty() ? "" : "; pick one with ordinal or index"));
            return -1;
        }
        return ofType.get(0);
    }

    private static boolean marked(Param<?> param) {
        return param.self() || param.share() != null || param.thrown();
    }

    private boolean isCallbackInfo(T type) {
        String name = types.erasedName(type);
        return name.equals(Names.CALLBACK_INFO) || name.equals(Names.CALLBACK_INFO_RETURNABLE);
    }

    private static String box(String erasedName) {
        return Names.BOXES.getOrDefault(erasedName, erasedName);
    }

    private void error(Object node, String message) {
        analysis.error(node, message);
    }
}
