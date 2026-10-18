package com.pzlauncher.zabric.processor.glue;

import com.pzlauncher.zabric.listing.HookListingFormat;
import com.pzlauncher.zabric.processor.glue.HandlerBinder.Resolved;
import com.pzlauncher.zabric.processor.glue.HandlerBinder.Shape;
import com.pzlauncher.zabric.processor.glue.HookDecls.Handler;
import com.pzlauncher.zabric.processor.glue.HookDecls.HookClass;
import com.pzlauncher.zabric.processor.glue.HookDecls.Member;
import com.pzlauncher.zabric.processor.glue.HookDecls.TargetClass;
import com.pzlauncher.zabric.processor.glue.PatchBuilder.Site;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Checks one compilation's hook classes against their targets and the loader's matcher, and builds their glue. */
public final class HookAnalyzer<T> {
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private final Analysis<T> analysis;
    private final HandlerBinder<T> binder;
    private final PatchBuilder<T> builder;

    public HookAnalyzer(TypeSystem<T> types, Reporter reporter) {
        analysis = new Analysis<>(types, reporter);
        binder = new HandlerBinder<>(analysis);
        builder = new PatchBuilder<>(analysis);
    }

    /** {@code packageName}: the mod's javaPkgName. Null when anything was reported. */
    public GlueModel.Unit analyze(String modId, String packageName, String className,
                                  List<HookClass<T>> hookClasses) {
        analysis.reset();
        if (modId.isEmpty() || modId.chars().anyMatch(Character::isWhitespace)) {
            analysis.error(null, "the mod id '" + modId + "' is empty or contains whitespace");
        }
        if (!PACKAGE.matcher(packageName).matches()) {
            analysis.error(null, "'" + packageName + "' is not a Java package name");
        }
        if (!IDENTIFIER.matcher(className).matches()) {
            analysis.error(null, "'" + className + "' is not a Java class name");
        }

        Map<String, Site<T>> sites = new LinkedHashMap<>();
        List<HookClass<T>> sorted = hookClasses.stream().sorted(Comparator.comparing(HookClass::binaryName)).toList();
        for (HookClass<T> hookClass : sorted) {
            collect(hookClass, sites);
        }
        List<Site<T>> ordered = sites.values().stream()
                .sorted(Comparator.comparing((Site<T> s) -> s.targetClass.binaryName()).thenComparingInt(s -> s.order))
                .toList();

        List<GlueModel.Patch> patches = new ArrayList<>();
        Set<String> patchNames = new HashSet<>();
        Map<String, List<GlueModel.Patch>> byHookClass = new TreeMap<>();
        for (Site<T> site : ordered) {
            GlueModel.Patch patch = builder.patch(site, packageName, patchNames);
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
                String gate = hookClass.gate() == null ? HookListingFormat.DEFAULT_GATE : hookClass.gate().binaryName();
                listings.add(new GlueModel.Listing(hookClass.binaryName(), gate, own));
            }
        }
        return analysis.failed() ? null : new GlueModel.Unit(modId, packageName, className, patches, listings);
    }

    private void collect(HookClass<T> hookClass, Map<String, Site<T>> sites) {
        if (hookClass.targets().isEmpty()) {
            analysis.error(hookClass.node(), Messages.noTargets(hookClass.sourceName()));
        }
        Set<String> seen = new HashSet<>();
        for (TargetClass<T> target : hookClass.targets()) {
            if (!seen.add(target.binaryName())) {
                analysis.error(hookClass.node(), hookClass.sourceName() + ": target class " + target.binaryName()
                        + " is named twice");
            }
        }
        if (hookClass.handlers().isEmpty()) {
            analysis.error(hookClass.node(), Messages.noHandlers(hookClass.sourceName()));
        }
        for (Handler<T> handler : hookClass.handlers()) {
            Shape shape = binder.shape(hookClass, handler);
            if (shape == null) {
                continue;
            }
            for (TargetClass<T> target : hookClass.targets()) {
                int index = binder.member(hookClass, handler, shape, target);
                if (index < 0) {
                    continue;
                }
                Member<T> member = target.members().get(index);
                Resolved resolved = binder.bind(hookClass, handler, shape, target, member);
                if (resolved == null) {
                    continue;
                }
                String descriptor = analysis.descriptor(member);
                String key = target.binaryName() + " " + member.name() + descriptor;
                String label = target.binaryName() + "." + member.name() + descriptor;
                sites.computeIfAbsent(key, k -> new Site<>(target, member, index, label)).byClass
                        .computeIfAbsent(hookClass.binaryName(), k -> new ArrayList<>()).add(resolved);
            }
        }
    }
}
