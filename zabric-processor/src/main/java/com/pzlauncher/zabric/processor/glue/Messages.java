package com.pzlauncher.zabric.processor.glue;

import java.util.Map;

/** Diagnostic texts; every front end reports them verbatim. */
public final class Messages {
    private Messages() {
    }

    private static final String DISCLAIMER = "it has no Zabric gate, error catching or target check, and Zabric takes"
            + " no responsibility when it breaks, e.g. when Zabric moves to another loader";

    public static String loaderPatch(String className) {
        return className + " is a ZombieBuddy @Patch class: hook through Zabric's API instead (@Hooks with @Inject,"
                + " @ModifyReturnValue or @ModifyVariable). Adding @UnsafeLoaderPatch lets it compile, but that is not"
                + " recommended: " + DISCLAIMER + ".";
    }

    public static String unsafeLoaderPatch(String className) {
        return className + " is a ZombieBuddy @Patch class marked @UnsafeLoaderPatch. Not recommended: " + DISCLAIMER
                + ". Hook through Zabric's API (@Hooks) instead.";
    }

    public static String markerWithoutPatch(String className) {
        return className + " has @UnsafeLoaderPatch but no ZombieBuddy @Patch; remove the marker";
    }

    public static String missingOption(String option) {
        return "the annotation processor option " + option + " is missing (javac -A" + option + "=...)";
    }

    public static String handlerOutsideHooks(String handler, String annotation) {
        return handler + ": " + annotation + " handlers must be in a class annotated with @Hooks";
    }

    public static String parameterOutsideHandler(String method, String annotation) {
        return method + ": " + annotation + " is only for parameters of @Inject, @ModifyReturnValue and"
                + " @ModifyVariable handlers";
    }

    public static String severalKinds(String handler) {
        return handler + ": a handler has exactly one of @Inject, @ModifyReturnValue and @ModifyVariable";
    }

    public static String laterRound(String hookClass) {
        return hookClass + ": @Hooks classes generated in a later processing round are not supported";
    }

    public static String gateNotFound(String hookClass, String gate) {
        return hookClass + ": the gate class " + gate + " cannot be resolved";
    }

    public static String badGate(String hookClass, String gate) {
        return hookClass + ": the gate " + gate + " must be a public HookGate with a public static final INSTANCE of a"
                + " HookGate type (or a Kotlin object)";
    }

    public static String handlerTypeParameters(String handler) {
        return handler + ": a handler cannot have type parameters";
    }

    public static String targetNotFound(String hookClass, String target) {
        return hookClass + ": target class " + target + " is not on the compile classpath";
    }

    public static String noTargets(String hookClass) {
        return hookClass + ": @Hooks names no target class (value or targets)";
    }

    public static String noHandlers(String hookClass) {
        return hookClass + ": a @Hooks class needs at least one @Inject, @ModifyReturnValue or @ModifyVariable"
                + " handler";
    }

    private static final Map<String, String> MIXIN_ADVICE = Map.ofEntries(
            Map.entry("ModifyArg", "to change a parameter of the target, use @ModifyVariable(at = @At(\"HEAD\"),"
                    + " argsOnly = true)"),
            Map.entry("ModifyArgs", "to change a parameter of the target, use @ModifyVariable(at = @At(\"HEAD\"),"
                    + " argsOnly = true)"),
            Map.entry("Redirect", "only HEAD, RETURN and FINALLY of the target method can be hooked"),
            Map.entry("WrapOperation", "only HEAD, RETURN and FINALLY of the target method can be hooked"),
            Map.entry("WrapWithCondition", "only HEAD, RETURN and FINALLY of the target method can be hooked"),
            Map.entry("ModifyExpressionValue", "only HEAD, RETURN and FINALLY of the target method can be hooked"),
            Map.entry("ModifyReceiver", "only HEAD, RETURN and FINALLY of the target method can be hooked"),
            Map.entry("ModifyConstant", "only HEAD, RETURN and FINALLY of the target method can be hooked"),
            Map.entry("WrapMethod", "use @Inject at HEAD with a FINALLY handler that undoes what it changed"),
            Map.entry("Overwrite", "use a cancellable @Inject at HEAD that sets the return value"),
            Map.entry("Local", "only the target's arguments are available, not its local variables"),
            Map.entry("Shadow", "reach the target's members directly, made public with access widening"),
            Map.entry("Accessor", "reach the target's members directly, made public with access widening"),
            Map.entry("Invoker", "reach the target's members directly, made public with access widening"),
            Map.entry("Unique", "hooks add no members to the target"),
            Map.entry("Mixin", "use @Hooks"));

    public static boolean isMixin(String annotation) {
        return annotation.startsWith("org.spongepowered.asm.mixin.")
                || annotation.startsWith("com.llamalad7.mixinextras.");
    }

    public static String unsupportedMixin(String where, String annotation) {
        String simple = annotation.substring(annotation.lastIndexOf('.') + 1);
        String instead = MIXIN_ADVICE.getOrDefault(simple, "Zabric hooks support @Inject, @ModifyReturnValue and"
                + " @ModifyVariable at HEAD, RETURN and FINALLY");
        return where + ": @" + simple + " (" + annotation + ") is not supported by Zabric hooks; " + instead;
    }
}
