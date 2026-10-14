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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks the hook classes of one compilation against their targets and the loader's rules, and builds the
 * {@link GlueModel}. Every problem is reported, naming the handler or class; {@link #analyze} then returns null.
 */
public final class HookAnalyzer<T> {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern SHARE_KEY = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern METHOD = Pattern.compile("([^(]+)(\\(.*)?");

    private static final String INJECT_SIGNATURE = "void h([@Self T self,] [target parameters...,]"
            + " CallbackInfo ci [, @Thrown Throwable t] [, @Share(\"key\") LocalRef<S> s...]), with"
            + " CallbackInfoReturnable<R> for a non-void target";
    private static final String MODIFY_SIGNATURE = " h(%1$s original [, @Self T self] [, target parameters...]"
            + " [, @Share(\"key\") LocalRef<S> s...])";

    private final TypeSystem<T> types;
    private final Reporter reporter;
    private boolean failed;

    public HookAnalyzer(TypeSystem<T> types, Reporter reporter) {
        this.types = types;
        this.reporter = reporter;
    }

    /**
     * The glue of {@code hookClasses}, the hook classes of one compilation, in class {@code className} of
     * {@code packageName} (the mod's javaPkgName); null when anything was reported.
     */
    public GlueModel.Unit analyze(String modId, String packageName, String className,
                                  List<HookClass<T>> hookClasses) {
        failed = false;
        if (modId.isEmpty() || modId.chars().anyMatch(Character::isWhitespace)) {
            error(null, "the mod id '" + modId + "' is empty or contains whitespace");
        }
        if (!PACKAGE.matcher(packageName).matches()) {
            error(null, "'" + packageName + "' is not a Java package name");
        }
        if (!IDENTIFIER.matcher(className).matches()) {
            error(null, "'" + className + "' is not a Java class name");
        }

        Map<String, Site> sites = new LinkedHashMap<>();
        List<HookClass<T>> sorted = hookClasses.stream().sorted(Comparator.comparing(HookClass::binaryName)).toList();
        for (HookClass<T> hookClass : sorted) {
            collect(hookClass, sites);
        }
        List<Site> ordered = sites.values().stream()
                .sorted(Comparator.comparing((Site s) -> s.targetClass.binaryName()).thenComparingInt(s -> s.order))
                .toList();

        List<GlueModel.Patch> patches = new ArrayList<>();
        Set<String> patchNames = new HashSet<>();
        Map<String, List<GlueModel.Patch>> byHookClass = new TreeMap<>();
        for (Site site : ordered) {
            GlueModel.Patch patch = patch(site, packageName, patchNames);
            if (patch == null) {
                continue;
            }
            patches.add(patch);
            site.byClass.keySet().forEach(c -> byHookClass.computeIfAbsent(c, k -> new ArrayList<>()).add(patch));
        }
        List<GlueModel.Listing> listings = new ArrayList<>();
        for (HookClass<T> hookClass : sorted) {
            List<GlueModel.Patch> own = byHookClass.get(hookClass.binaryName());
            if (own != null) {
                String gate = hookClass.gate() == null ? Names.LISTING_DEFAULT_GATE : hookClass.gate().binaryName();
                listings.add(new GlueModel.Listing(hookClass.binaryName(), gate, own));
            }
        }
        return failed ? null : new GlueModel.Unit(modId, packageName, className, patches, listings);
    }

    // ---- per hook class and handler ----

    /** A target method and the handlers on it, by hook class binary name. */
    private final class Site {
        final TargetClass<T> targetClass;
        final Member<T> member;
        final int order;
        final Map<String, List<Resolved>> byClass = new TreeMap<>();

        Site(TargetClass<T> targetClass, Member<T> member, int order) {
            this.targetClass = targetClass;
            this.member = member;
            this.order = order;
        }

        String label() {
            return targetClass.binaryName() + "." + member.name() + descriptor(member);
        }
    }

    /** A handler on one target method. {@code named}: types from the handler that the glue writes out. */
    private record Resolved(String label, Object node, HookClass<?> hookClass, Kind kind, boolean always, At at,
                            boolean cancellable, String name, List<Value> values, String returnable,
                            int modifiedArg, Map<String, String> shares, List<Object> named) {
    }

    private record Shape(At at, String method, String descriptor) {
    }

    private void collect(HookClass<T> hookClass, Map<String, Site> sites) {
        if (hookClass.targets().isEmpty()) {
            error(hookClass.node(), Messages.noTargets(hookClass.sourceName()));
        }
        Set<String> seen = new HashSet<>();
        for (TargetClass<T> target : hookClass.targets()) {
            if (!seen.add(target.binaryName())) {
                error(hookClass.node(), hookClass.sourceName() + ": target class " + target.binaryName()
                        + " is named twice");
            }
        }
        if (hookClass.handlers().isEmpty()) {
            error(hookClass.node(), Messages.noHandlers(hookClass.sourceName()));
        }
        for (Handler<T> handler : hookClass.handlers()) {
            Shape shape = shape(hookClass, handler);
            if (shape == null) {
                continue;
            }
            for (TargetClass<T> target : hookClass.targets()) {
                int index = member(hookClass, handler, shape, target);
                if (index < 0) {
                    continue;
                }
                Member<T> member = target.members().get(index);
                Resolved resolved = bind(hookClass, handler, shape, target, member);
                if (resolved == null) {
                    continue;
                }
                String key = target.binaryName() + " " + member.name() + descriptor(member);
                sites.computeIfAbsent(key, k -> new Site(target, member, index)).byClass
                        .computeIfAbsent(hookClass.binaryName(), k -> new ArrayList<>()).add(resolved);
            }
        }
    }

    /** What does not depend on the target class. */
    private Shape shape(HookClass<T> hookClass, Handler<T> handler) {
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
        if (!name.equals("<init>") && !IDENTIFIER.matcher(name).matches()) {
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

    /** The target member's index in {@code target.members()}, or -1 after reporting why there is none. */
    private int member(HookClass<T> hookClass, Handler<T> handler, Shape shape, TargetClass<T> target) {
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
                if (descriptor(target.members().get(i)).equals(shape.descriptor())) {
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
        String site = target.binaryName() + "." + shape.method() + descriptor(member);
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
        if (handler.kind() == Kind.MODIFY_RETURN_VALUE && isVoid(member.returnType())) {
            error(handler.node(), label + ": " + site + " returns nothing; @ModifyReturnValue needs a return"
                    + " value");
            return -1;
        }
        return found;
    }

    private String overloads(TargetClass<T> target, List<Integer> indexes) {
        return indexes.stream().map(i -> {
            Member<T> m = target.members().get(i);
            return m.name() + descriptor(m);
        }).collect(Collectors.joining(", "));
    }

    /** Matches the handler's parameters to what the glue passes. */
    private Resolved bind(HookClass<T> hookClass, Handler<T> handler, Shape shape, TargetClass<T> target,
                          Member<T> member) {
        String label = label(hookClass, handler);
        Object node = handler.node();
        String site = target.binaryName() + "." + member.name() + descriptor(member);
        List<Param<T>> params = handler.params();
        List<Value> values = new ArrayList<>();
        List<Object> named = new ArrayList<>();
        Map<String, String> shares = new LinkedHashMap<>();
        String returnable = null;
        int modifiedArg = -1;
        T targetReturn = member.returnType();
        boolean ok = true;
        String signature = handler.kind() == Kind.INJECT ? INJECT_SIGNATURE
                : (handler.kind() == Kind.MODIFY_RETURN_VALUE ? "R" + MODIFY_SIGNATURE.formatted("R")
                : "P" + MODIFY_SIGNATURE.formatted("P"));

        int i = 0;
        if (handler.kind() != Kind.INJECT) {
            if (params.isEmpty() || marked(params.get(0))) {
                error(node, label + ": the first parameter is the original value; the signature is " + signature);
                return null;
            }
            values.add(Value.of(Value.Kind.ORIGINAL));
            i = 1;
        }
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
        } else {
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

        if (handler.kind() == Kind.INJECT) {
            if (i >= params.size() || marked(params.get(i)) || !isCallbackInfo(params.get(i).type())) {
                error(node, label + ": needs a CallbackInfo parameter after the target's parameters; the signature"
                        + " is " + signature);
                return null;
            }
            Param<T> ci = params.get(i);
            String ciType = types.erasedName(ci.type());
            if (isVoid(targetReturn)) {
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
        }

        for (; i < params.size(); i++) {
            Param<T> param = params.get(i);
            if (param.share() == null) {
                error(node, label + ": parameter " + param.name() + " is out of place; the signature is "
                        + signature);
                return null;
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

        if (handler.kind() == Kind.INJECT && !isVoid(handler.returnType())) {
            error(node, label + ": an @Inject handler returns void");
            ok = false;
        }
        if (handler.kind() == Kind.MODIFY_RETURN_VALUE && ok) {
            Param<T> original = params.get(0);
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
        if (handler.kind() == Kind.MODIFY_VARIABLE && ok) {
            modifiedArg = modifiedArg(label, node, handler, member, site);
            if (modifiedArg < 0) {
                ok = false;
            } else {
                T arg = member.params().get(modifiedArg);
                if (!types.isSubtype(handler.returnType(), arg)) {
                    error(node, label + ": returns " + types.erasedName(handler.returnType()) + ", but parameter "
                            + modifiedArg + " of " + site + " is " + types.erasedName(arg));
                    ok = false;
                }
            }
        }
        if (!ok) {
            return null;
        }
        return new Resolved(label, node, hookClass, handler.kind(), handler.always(), shape.at(),
                handler.cancellable(), handler.name(), values, returnable, modifiedArg, shares, named);
    }

    /** As Mixin: {@code index} is a local variable slot, else {@code ordinal} among the arguments of the type. */
    private int modifiedArg(String label, Object node, Handler<T> handler, Member<T> member, String site) {
        String type = types.erasedName(handler.params().get(0).type());
        List<T> args = member.params();
        if (handler.index() >= 0) {
            int slot = member.isStatic() ? 0 : 1;
            for (int k = 0; k < args.size(); k++) {
                if (slot == handler.index()) {
                    if (!types.erasedName(args.get(k)).equals(type)) {
                        error(node, label + ": slot " + handler.index() + " of " + site + " holds parameter " + k
                                + " of type " + types.erasedName(args.get(k)) + ", not " + type);
                        return -1;
                    }
                    return k;
                }
                String name = types.erasedName(args.get(k));
                slot += name.equals("long") || name.equals("double") ? 2 : 1;
            }
            error(node, label + ": index " + handler.index() + " is not the local variable slot of a parameter of "
                    + site + (member.isStatic() ? "" : " (slot 0 is this)"));
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

    // ---- per target method ----

    private GlueModel.Patch patch(Site site, String packageName, Set<String> patchNames) {
        Member<T> member = site.member;
        List<GlueModel.Block> blocks = new ArrayList<>();
        boolean ok = true;
        boolean usesSelf = false;
        boolean hasExit = false;
        boolean cancellableHead = false;
        Set<Integer> writable = new HashSet<>();
        List<Resolved> all = new ArrayList<>();
        for (var entry : site.byClass.entrySet()) {
            List<Resolved> handlers = entry.getValue();
            all.addAll(handlers);
            HookClass<?> hookClass = handlers.get(0).hookClass();
            List<Resolved> always = handlers.stream().filter(Resolved::always).toList();
            if (!always.isEmpty() && always.size() != handlers.size()) {
                Resolved other = handlers.stream().filter(r -> !r.always()).findFirst().orElseThrow();
                error(always.get(0).node(), always.get(0).label() + ": always = true handlers must be the only"
                        + " handlers of their hook class on " + site.label() + ", but " + other.label()
                        + " is not always; move one of them to another hook class");
                ok = false;
            }
            Map<String, String> shares = new LinkedHashMap<>();
            for (Resolved handler : handlers) {
                for (var share : handler.shares().entrySet()) {
                    String before = shares.putIfAbsent(share.getKey(), share.getValue());
                    if (before != null && !before.equals(share.getValue())) {
                        error(handler.node(), handler.label() + ": @Share(\"" + share.getKey() + "\") is LocalRef<"
                                + share.getValue() + "> here but LocalRef<" + before + "> in another handler of "
                                + hookClass.simpleName() + " on " + site.label());
                        ok = false;
                    }
                }
            }
            List<GlueModel.Call> calls = new ArrayList<>();
            for (Resolved handler : handlers) {
                usesSelf |= handler.values().contains(Value.of(Value.Kind.SELF));
                hasExit |= handler.at() != At.HEAD;
                cancellableHead |= handler.at() == At.HEAD && handler.cancellable();
                if (handler.modifiedArg() >= 0) {
                    writable.add(handler.modifiedArg());
                }
                calls.add(new GlueModel.Call(handler.name(), handler.kind(), handler.at(), handler.cancellable(),
                        handler.returnable(), handler.modifiedArg(), handler.values()));
            }
            String gate = hookClass.gate() == null ? "MOD.defaultGate()" : hookClass.gate().sourceName() + ".INSTANCE";
            blocks.add(new GlueModel.Block(hookClass.simpleName(), hookClass.callee(), gate, !always.isEmpty(),
                    shares.entrySet().stream().map(e -> new GlueModel.Share(e.getKey(), e.getValue())).toList(),
                    calls));
        }
        if (!ok) {
            return null;
        }

        String handlers = all.stream().map(Resolved::label).distinct().collect(Collectors.joining(", "));
        Object node = all.get(0).node();
        boolean nonVoid = !isVoid(member.returnType());
        hasExit |= cancellableHead && nonVoid;

        List<GlueModel.Arg> args = new ArrayList<>();
        List<T> enterMap = new ArrayList<>();
        List<T> exitMap = new ArrayList<>();
        List<T> named = new ArrayList<>();
        boolean passesArgs = all.stream().anyMatch(r -> r.values().stream().anyMatch(v -> v.kind() == Value.Kind.ARG));
        for (int k = 0; k < member.params().size(); k++) {
            T type = member.params().get(k);
            boolean array = types.component(type) != null;
            boolean isWritable = writable.contains(k);
            // the loader compares a bound array with its component type, so it never matches unless bound as Object
            String binding = array && !isWritable ? Names.OBJECT : types.erasedName(type);
            args.add(new GlueModel.Arg(types.erasedName(type), binding, isWritable));
            enterMap.add(isWritable ? (array ? types.component(type) : type) : (array ? null : type));
            exitMap.add(array ? null : type);
            if (!binding.equals(Names.OBJECT) || passesArgs) {
                named.add(type);
            }
        }
        List<List<T>> maps = new ArrayList<>();
        if (!args.isEmpty()) {
            maps.add(enterMap);
            if (hasExit) {
                maps.add(exitMap);
            }
        }
        if (!checkMatcher(site, maps, node, handlers, writable)) {
            return null;
        }

        if (nonVoid && hasExit) {
            named.add(member.returnType());
        }
        if (usesSelf) {
            named.add(site.targetClass.type());
        }
        for (Resolved handler : all) {
            for (Object type : handler.named()) {
                @SuppressWarnings("unchecked") T t = (T) type;
                named.add(t);
            }
        }
        Set<String> reported = new HashSet<>();
        for (T type : named) {
            String problem = types.accessProblem(type, packageName);
            if (problem != null && reported.add(problem)) {
                error(node, handlers + ": the glue in package " + packageName + " must name "
                        + types.erasedName(type) + ", but " + problem + "; make it public with access widening");
                ok = false;
            }
        }
        if (!ok) {
            return null;
        }

        String ownerSource = types.erasedName(site.targetClass.type());
        GlueModel.Target target = new GlueModel.Target(site.targetClass.binaryName(), ownerSource, member.name(),
                descriptor(member), args, types.erasedName(member.returnType()));
        return new GlueModel.Patch(patchName(site, patchNames), target, blocks);
    }

    private boolean checkMatcher(Site site, List<List<T>> maps, Object node, String handlers, Set<Integer> writable) {
        boolean noParam = site.member.params().isEmpty();
        List<String> selected = new ArrayList<>();
        boolean intended = false;
        for (Member<T> m : site.targetClass.members()) {
            if (!m.name().equals(site.member.name()) || !m.hasBody()
                    || !LoaderMatcher.selects(types, m.params(), maps, noParam)) {
                continue;
            }
            if (m == site.member) {
                intended = true;
            } else {
                selected.add(m.name() + descriptor(m) + (m.bridge() ? " (a bridge method the compiler adds)" : ""));
            }
        }
        if (!intended) {
            boolean arrays = writable.stream().anyMatch(k -> types.component(site.member.params().get(k)) != null);
            error(node, handlers + ": ZombieBuddy's patch matcher would not select " + site.label()
                    + " for the generated glue" + (arrays ? " (it compares an array argument with the array's"
                    + " component type, so @ModifyVariable cannot change an array argument other than Object[])" : ""));
            return false;
        }
        if (!selected.isEmpty()) {
            error(node, handlers + ": ZombieBuddy's patch matcher would also apply the glue for " + site.label()
                    + " to " + String.join(", ", selected) + ": it selects every method of that name whose first "
                    + site.member.params().size() + " parameters accept the bound arguments. Zabric cannot hook "
                    + site.label() + " alone");
            return false;
        }
        return true;
    }

    private String patchName(Site site, Set<String> taken) {
        String owner = site.targetClass.binaryName();
        String simple = owner.substring(owner.lastIndexOf('.') + 1).replace('$', '_');
        String method = site.member.name().equals("<init>") ? "init" : site.member.name();
        String base = identifier(simple + "_" + method);
        String name = base;
        for (int n = 2; !taken.add(name); n++) {
            name = base + "_" + n;
        }
        return name;
    }

    // ---- helpers ----

    private static String identifier(String name) {
        StringBuilder out = new StringBuilder();
        name.codePoints().forEach(c -> out.appendCodePoint(Character.isJavaIdentifierPart(c) ? c : '_'));
        if (!Character.isJavaIdentifierStart(out.codePointAt(0))) {
            out.insert(0, '_');
        }
        return out.toString();
    }

    private static boolean marked(Param<?> param) {
        return param.self() || param.share() != null || param.thrown();
    }

    private boolean isCallbackInfo(T type) {
        String name = types.erasedName(type);
        return name.equals(Names.CALLBACK_INFO) || name.equals(Names.CALLBACK_INFO_RETURNABLE);
    }

    private boolean isVoid(T type) {
        return types.erasedName(type).equals("void");
    }

    private static String box(String erasedName) {
        return Names.BOXES.getOrDefault(erasedName, erasedName);
    }

    private String descriptor(Member<T> member) {
        StringBuilder out = new StringBuilder("(");
        member.params().forEach(p -> out.append(types.descriptor(p)));
        return out.append(')').append(types.descriptor(member.returnType())).toString();
    }

    private static String label(HookClass<?> hookClass, Handler<?> handler) {
        return hookClass.simpleName() + "." + handler.name();
    }

    private void error(Object node, String message) {
        failed = true;
        reporter.error(node, message);
    }
}
