package com.pzlauncher.zabric.processor.glue;

import com.pzlauncher.zabric.processor.glue.GlueModel.At;
import com.pzlauncher.zabric.processor.glue.GlueModel.Value;
import com.pzlauncher.zabric.processor.glue.HandlerBinder.Resolved;
import com.pzlauncher.zabric.processor.glue.HookDecls.HookClass;
import com.pzlauncher.zabric.processor.glue.HookDecls.Member;
import com.pzlauncher.zabric.processor.glue.HookDecls.TargetClass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Builds the loader patch of one target method from the handlers bound to it, as the loader will match it. */
final class PatchBuilder<T> {
    private final Analysis<T> analysis;
    private final TypeSystem<T> types;

    PatchBuilder(Analysis<T> analysis) {
        this.analysis = analysis;
        this.types = analysis.types;
    }

    /** {@code byClass} is sorted: hook classes on one target run in name order. */
    static final class Site<T> {
        final TargetClass<T> targetClass;
        final Member<T> member;
        final int order;
        final String label;
        final Map<String, List<Resolved>> byClass = new TreeMap<>();

        Site(TargetClass<T> targetClass, Member<T> member, int order, String label) {
            this.targetClass = targetClass;
            this.member = member;
            this.order = order;
            this.label = label;
        }
    }

    /** Null after reporting. */
    GlueModel.Patch patch(Site<T> site, String packageName, Set<String> patchNames) {
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
                        + " handlers of their hook class on " + site.label + ", but " + other.label()
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
                                + hookClass.simpleName() + " on " + site.label);
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
        boolean nonVoid = !analysis.isVoid(member.returnType());
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
                analysis.descriptor(member), args, types.erasedName(member.returnType()));
        return new GlueModel.Patch(patchName(site, patchNames), target, blocks);
    }

    private boolean checkMatcher(Site<T> site, List<List<T>> maps, Object node, String handlers,
                                 Set<Integer> writable) {
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
                selected.add(m.name() + analysis.descriptor(m)
                        + (m.bridge() ? " (a bridge method the compiler adds)" : ""));
            }
        }
        if (!intended) {
            boolean arrays = writable.stream().anyMatch(k -> types.component(site.member.params().get(k)) != null);
            error(node, handlers + ": ZombieBuddy's patch matcher would not select " + site.label
                    + " for the generated glue" + (arrays ? " (it compares an array argument with the array's"
                    + " component type, so @ModifyVariable cannot change an array argument other than Object[])" : ""));
            return false;
        }
        if (!selected.isEmpty()) {
            error(node, handlers + ": ZombieBuddy's patch matcher would also apply the glue for " + site.label
                    + " to " + String.join(", ", selected) + ": it selects every method of that name whose first "
                    + site.member.params().size() + " parameters accept the bound arguments. Zabric cannot hook "
                    + site.label + " alone");
            return false;
        }
        return true;
    }

    private static String patchName(Site<?> site, Set<String> taken) {
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

    private static String identifier(String name) {
        StringBuilder out = new StringBuilder();
        name.codePoints().forEach(c -> out.appendCodePoint(Character.isJavaIdentifierPart(c) ? c : '_'));
        if (!Character.isJavaIdentifierStart(out.codePointAt(0))) {
            out.insert(0, '_');
        }
        return out.toString();
    }

    private void error(Object node, String message) {
        analysis.error(node, message);
    }
}
