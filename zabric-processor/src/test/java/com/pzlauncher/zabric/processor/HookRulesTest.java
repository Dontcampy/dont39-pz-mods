package com.pzlauncher.zabric.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every rule the processor checks, with the message it reports; each names the handler or class. */
class HookRulesTest {
    private static final String TARGET = """
            package t;

            public class Target {
                public Target() {
                }

                public int compute(int x) {
                    return x;
                }

                public int over(int x) {
                    return x;
                }

                public int over(String s) {
                    return 0;
                }

                public void prefix(int x) {
                }

                public void prefix(int x, String s) {
                }

                public static void stat(int x) {
                }

                public void arr(String[] a) {
                }

                public void two(int a, int b) {
                }

                public void wide(long a, int b) {
                }

                public void pkgArg(Pkg p) {
                }

                public String name(String s) {
                    return s;
                }
            }
            """;
    private static final String PKG = """
            package t;

            class Pkg {
            }
            """;
    private static final String SUPPLIER = """
            package t;

            public class Sup implements java.util.function.Supplier<String> {
                @Override
                public String get() {
                    return "";
                }
            }
            """;
    private static final String IMPORTS = """
            package h;

            import com.pzlauncher.zabric.hook.*;
            import t.*;

            """;

    @TempDir
    Path dir;

    private Compiler.Result compile(String hooks, String... more) {
        String[] sources = new String[6 + more.length];
        sources[0] = "t/Target.java";
        sources[1] = TARGET;
        sources[2] = "t/Pkg.java";
        sources[3] = PKG;
        sources[4] = "h/H.java";
        sources[5] = IMPORTS + hooks;
        System.arraycopy(more, 0, sources, 6, more.length);
        return Compiler.compile(dir, Compiler.OPTIONS, sources);
    }

    /** A hook class H on Target with {@code body}. */
    private Compiler.Result hooks(String body, String... more) {
        return compile("@Hooks(Target.class)\npublic final class H {\n" + body + "\n}\n", more);
    }

    private static void assertError(Compiler.Result result, String... parts) {
        assertFalse(result.success());
        boolean found = result.errors().stream().anyMatch(e -> List.of(parts).stream().allMatch(e::contains));
        assertTrue(found, "no error with " + List.of(parts) + " in " + result.errors());
    }

    private static void assertCompiles(Compiler.Result result) {
        assertEquals(List.of(), result.errors());
        assertTrue(result.success());
    }

    private String glue(Compiler.Result result) {
        return result.source("glue/TestModGlue.java");
    }

    // ---- targets ----

    @Test
    void unresolvedTarget() {
        Compiler.Result result = compile("""
                @Hooks(targets = "t.Missing")
                public final class H {
                    @Inject(method = "x", at = @At("HEAD"))
                    public static void h(CallbackInfo ci) {
                    }
                }
                """);
        assertError(result, "h.H: target class t.Missing is not on the compile classpath");
    }

    @Test
    void targetByBinaryName() {
        Compiler.Result result = compile("""
                @Hooks(targets = "t.Target")
                public final class H {
                    @Inject(method = "prefix(I)V", at = @At("HEAD"))
                    public static void h(CallbackInfo ci) {
                    }
                }
                """);
        assertError(result, "H.h: ZombieBuddy's patch matcher would also apply the glue for t.Target.prefix(I)V to"
                + " prefix(ILjava/lang/String;)V");
    }

    @Test
    void hookClassNeedsHandlers() {
        assertError(hooks(""), "h.H: a @Hooks class needs at least one @Inject, @ModifyReturnValue or"
                + " @ModifyVariable handler");
    }

    @Test
    void overloadsInOneCompilationGetTheirOwnPatchClasses() {
        Compiler.Result result = hooks("""
                @Inject(method = "over(I)I", at = @At("HEAD"))
                public static void a(CallbackInfoReturnable<Integer> ci) {
                }

                @Inject(method = "over(Ljava/lang/String;)I", at = @At("HEAD"))
                public static void b(CallbackInfoReturnable<Integer> ci) {
                }
                """);
        assertCompiles(result);
        assertTrue(glue(result).contains("public static final class Target_over {"));
        assertTrue(glue(result).contains("public static final class Target_over_2 {"));
    }

    @Test
    void missingMethod() {
        assertError(hooks("""
                @Inject(method = "nope", at = @At("HEAD"))
                public static void h(CallbackInfo ci) {
                }
                """), "H.h: t.Target declares no method nope");
    }

    @Test
    void ambiguousMethodNeedsADescriptor() {
        assertError(hooks("""
                @Inject(method = "over", at = @At("HEAD"))
                public static void h(CallbackInfoReturnable<Integer> ci) {
                }
                """), "H.h: t.Target declares 2 methods named over: over(I)I, over(Ljava/lang/String;)I; name one"
                + " with \"over(descriptor)\"");
    }

    @Test
    void descriptorPicksTheOverload() {
        Compiler.Result result = hooks("""
                @Inject(method = "over(Ljava/lang/String;)I", at = @At("HEAD"))
                public static void h(String s, CallbackInfoReturnable<Integer> ci) {
                }
                """);
        assertCompiles(result);
        assertTrue(glue(result).contains("@Patch.Argument(0) java.lang.String arg0"));
    }

    @Test
    void unknownDescriptor() {
        assertError(hooks("""
                @Inject(method = "over(J)I", at = @At("HEAD"))
                public static void h(CallbackInfoReturnable<Integer> ci) {
                }
                """), "H.h: t.Target declares no over(J)I; it has over(I)I, over(Ljava/lang/String;)I");
    }

    @Test
    void loaderMatcherWouldAlsoSelectALongerOverload() {
        assertError(hooks("""
                @Inject(method = "prefix(I)V", at = @At("HEAD"))
                public static void h(CallbackInfo ci) {
                }
                """), "H.h: ZombieBuddy's patch matcher would also apply the glue for t.Target.prefix(I)V to"
                + " prefix(ILjava/lang/String;)V", "Zabric cannot hook t.Target.prefix(I)V alone");
        // the longer overload is not selected by the glue of the shorter
        assertCompiles(hooks("""
                @Inject(method = "prefix(ILjava/lang/String;)V", at = @At("HEAD"))
                public static void h(CallbackInfo ci) {
                }
                """));
    }

    @Test
    void loaderMatcherWouldAlsoSelectABridge() {
        Compiler.Result result = compile("""
                @Hooks(Sup.class)
                public final class H {
                    @Inject(method = "get", at = @At("HEAD"))
                    public static void h(CallbackInfoReturnable<String> ci) {
                    }
                }
                """, "t/Sup.java", SUPPLIER);
        assertError(result, "H.h: ZombieBuddy's patch matcher would also apply the glue for t.Sup.get()"
                + "Ljava/lang/String; to get()Ljava/lang/Object; (a bridge method the compiler adds)");
    }

    @Test
    void inaccessibleTypeInTheGlue() {
        assertError(hooks("""
                @Inject(method = "pkgArg", at = @At("HEAD"))
                public static void h(CallbackInfo ci) {
                }
                """), "H.h: the glue in package glue must name t.Pkg, but t.Pkg is not public");
    }

    // ---- injection points and annotations ----

    @Test
    void unsupportedAt() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("INVOKE"))
                public static void h(CallbackInfoReturnable<Integer> ci) {
                }
                """), "H.h: @At(\"INVOKE\") is not supported; Zabric hooks run at HEAD, RETURN and FINALLY only");
    }

    @Test
    void unsupportedMixinAnnotation() {
        Compiler.Result result = hooks("""
                @org.spongepowered.asm.mixin.injection.Redirect
                public static void r() {
                }

                @Inject(method = "compute", at = @At("HEAD"))
                public static void h(CallbackInfoReturnable<Integer> ci) {
                }
                """, "org/spongepowered/asm/mixin/injection/Redirect.java", """
                package org.spongepowered.asm.mixin.injection;

                public @interface Redirect {
                }
                """);
        assertError(result, "H.r: @Redirect (org.spongepowered.asm.mixin.injection.Redirect) is not supported by"
                + " Zabric hooks; only HEAD, RETURN and FINALLY of the target method can be hooked");
    }

    @Test
    void oneHandlerAnnotationPerMethod() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("RETURN"))
                @ModifyReturnValue(method = "compute")
                public static int h(int original) {
                    return original;
                }
                """), "H.h: a handler has exactly one of @Inject, @ModifyReturnValue and @ModifyVariable");
    }

    @Test
    void handlerOutsideHooks() {
        Compiler.Result result = compile("""
                public final class H {
                    @Inject(method = "compute", at = @At("HEAD"))
                    public static void h(CallbackInfoReturnable<Integer> ci) {
                    }
                }
                """);
        assertError(result, "H.h: @Inject handlers must be in a class annotated with @Hooks");
    }

    @Test
    void parameterAnnotationOutsideHandler() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("HEAD"))
                public static void h(CallbackInfoReturnable<Integer> ci) {
                }

                public static void helper(@Self Target t) {
                }
                """), "H.helper: @Self is only for parameters of");
    }

    @Test
    void handlerMustBePublicStatic() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("HEAD"))
                static void h(CallbackInfoReturnable<Integer> ci) {
                }
                """), "H.h: a handler must be public static");
    }

    @Test
    void hookClassMustBePublic() {
        Compiler.Result result = compile("""
                @Hooks(Target.class)
                final class H {
                    @Inject(method = "compute", at = @At("HEAD"))
                    public static void h(CallbackInfoReturnable<Integer> ci) {
                    }
                }
                """);
        assertError(result, "h.H: a hook class must be public");
    }

    @Test
    void cancellableFinally() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("FINALLY"), cancellable = true)
                public static void h(CallbackInfoReturnable<Integer> ci) {
                }
                """), "H.h: a FINALLY handler cannot be cancellable");
    }

    @Test
    void thrownOnlyAtFinally() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("RETURN"))
                public static void h(CallbackInfoReturnable<Integer> ci, @Thrown Throwable t) {
                }
                """), "H.h: parameter t: @Thrown is only for @Inject handlers at FINALLY");
    }

    @Test
    void constructorsAtReturnOnly() {
        assertError(hooks("""
                @Inject(method = "<init>", at = @At("HEAD"))
                public static void h(CallbackInfo ci) {
                }
                """), "H.h: constructors can be hooked at RETURN only, not HEAD");
        assertError(hooks("""
                @Inject(method = "<init>", at = @At("FINALLY"))
                public static void h(CallbackInfo ci) {
                }
                """), "H.h: constructors can be hooked at RETURN only, not FINALLY");
        Compiler.Result result = hooks("""
                @Inject(method = "<init>", at = @At("RETURN"))
                public static void h(@Self Target self, CallbackInfo ci) {
                }
                """);
        assertCompiles(result);
        assertTrue(glue(result).contains("@Patch(className = \"t.Target\", methodName = \"<init>\", strictMatch ="
                + " true)\n    public static final class Target_init {"));
    }

    // ---- handler types ----

    @Test
    void targetParameterTypes() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("HEAD"))
                public static void h(String x, CallbackInfoReturnable<Integer> ci) {
                }
                """), "H.h: parameter x is java.lang.String, but parameter 0 of t.Target.compute(I)I is int");
        assertError(hooks("""
                @Inject(method = "compute", at = @At("HEAD"))
                public static void h(long x, CallbackInfoReturnable<Integer> ci) {
                }
                """), "H.h: parameter x is long, but parameter 0 of t.Target.compute(I)I is int");
    }

    @Test
    void targetParametersAllOrNone() {
        assertError(hooks("""
                @Inject(method = "prefix(ILjava/lang/String;)V", at = @At("HEAD"))
                public static void h(int x, CallbackInfo ci) {
                }
                """), "H.h: takes 1 of the 2 parameters of t.Target.prefix(ILjava/lang/String;)V; take all or none");
    }

    @Test
    void callbackInfoType() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("HEAD"))
                public static void h(CallbackInfoReturnable<Long> ci) {
                }
                """), "H.h: parameter ci: t.Target.compute(I)I returns int, so it takes a"
                + " CallbackInfoReturnable<java.lang.Integer>");
        assertError(hooks("""
                @Inject(method = "prefix(ILjava/lang/String;)V", at = @At("HEAD"))
                public static void h(CallbackInfoReturnable<Integer> ci) {
                }
                """), "H.h: parameter ci: t.Target.prefix(ILjava/lang/String;)V returns nothing, so it takes a"
                + " CallbackInfo");
        assertError(hooks("""
                @Inject(method = "compute", at = @At("HEAD"))
                public static void h(int x) {
                }
                """), "H.h: needs a CallbackInfo parameter");
    }

    @Test
    void injectReturnsVoid() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("HEAD"))
                public static int h(CallbackInfoReturnable<Integer> ci) {
                    return 0;
                }
                """), "H.h: an @Inject handler returns void");
    }

    @Test
    void selfType() {
        assertError(hooks("""
                @Inject(method = "stat", at = @At("HEAD"))
                public static void h(@Self Target self, CallbackInfo ci) {
                }
                """), "H.h: parameter self: @Self on the static t.Target.stat(I)V");
        assertError(hooks("""
                @Inject(method = "compute", at = @At("HEAD"))
                public static void h(@Self String self, CallbackInfoReturnable<Integer> ci) {
                }
                """), "H.h: parameter self: @Self is java.lang.String, which t.Target is not");
    }

    @Test
    void parameterOutOfPlace() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("HEAD"))
                public static void h(CallbackInfoReturnable<Integer> ci, int x) {
                }
                """), "H.h: parameter x is out of place; the signature is void h(");
    }

    @Test
    void modifyReturnValueTypes() {
        assertError(hooks("""
                @ModifyReturnValue(method = "compute")
                public static long h(int original) {
                    return original;
                }
                """), "H.h: returns long, but t.Target.compute(I)I returns int");
        assertError(hooks("""
                @ModifyReturnValue(method = "compute", at = @At("HEAD"))
                public static int h(int original) {
                    return original;
                }
                """), "H.h: @ModifyReturnValue runs at RETURN only, not HEAD");
        assertError(hooks("""
                @ModifyReturnValue(method = "stat")
                public static int h(int original) {
                    return original;
                }
                """), "H.h: t.Target.stat(I)V returns nothing; @ModifyReturnValue needs a return value");
    }

    @Test
    void modifyVariableNeedsArgsOnly() {
        assertError(hooks("""
                @ModifyVariable(method = "compute", at = @At("HEAD"))
                public static int h(int x) {
                    return x;
                }
                """), "H.h: @ModifyVariable needs argsOnly = true");
    }

    @Test
    void modifyVariableSelectsTheArgument() {
        assertError(hooks("""
                @ModifyVariable(method = "two", at = @At("HEAD"), argsOnly = true)
                public static int h(int x) {
                    return x;
                }
                """), "H.h: t.Target.two(II)V has 2 parameters of type int; pick one with ordinal or index");
        Compiler.Result byOrdinal = hooks("""
                @ModifyVariable(method = "two", at = @At("HEAD"), argsOnly = true, ordinal = 1)
                public static int h(int x) {
                    return x;
                }
                """);
        assertCompiles(byOrdinal);
        assertTrue(glue(byOrdinal).contains("arg1 = h.H.h(arg1);"));
        // slot 0 is this, a long takes two
        Compiler.Result bySlot = hooks("""
                @ModifyVariable(method = "wide", at = @At("HEAD"), argsOnly = true, index = 3)
                public static int h(int x) {
                    return x;
                }
                """);
        assertCompiles(bySlot);
        assertTrue(glue(bySlot).contains("arg1 = h.H.h(arg1);"));
        assertError(hooks("""
                @ModifyVariable(method = "wide", at = @At("HEAD"), argsOnly = true, index = 2)
                public static int h(int x) {
                    return x;
                }
                """), "H.h: index 2 is not the local variable slot of a parameter of t.Target.wide(JI)V");
    }

    @Test
    void arrayArgumentsBindAsObject() {
        Compiler.Result result = hooks("""
                @Inject(method = "arr", at = @At("HEAD"))
                public static void h(String[] a, CallbackInfo ci) {
                }
                """);
        assertCompiles(result);
        assertTrue(glue(result).contains("@Patch.Argument(0) java.lang.Object arg0"));
        assertTrue(glue(result).contains("h.H.h((java.lang.String[]) arg0, new CallbackInfo(\"arr\", false));"));
    }

    @Test
    void modifiedArrayArgumentIsNotMatched() {
        assertError(hooks("""
                @ModifyVariable(method = "arr", at = @At("HEAD"), argsOnly = true)
                public static String[] h(String[] a) {
                    return a;
                }
                """), "H.h: ZombieBuddy's patch matcher would not select t.Target.arr([Ljava/lang/String;)V",
                "@ModifyVariable cannot change an array argument other than Object[]");
    }

    // ---- share, always, gates ----

    @Test
    void shareKeysHaveOneType() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("HEAD"))
                public static void a(CallbackInfoReturnable<Integer> ci, @Share("k") LocalRef<Integer> k) {
                }

                @Inject(method = "compute", at = @At("RETURN"))
                public static void b(CallbackInfoReturnable<Integer> ci, @Share("k") LocalRef<String> k) {
                }
                """), "H.b: @Share(\"k\") is LocalRef<java.lang.String> here but LocalRef<java.lang.Integer> in"
                + " another handler of H on t.Target.compute(I)I");
    }

    @Test
    void shareIsALocalRef() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("HEAD"))
                public static void a(CallbackInfoReturnable<Integer> ci, @Share("k") Integer k) {
                }
                """), "H.a: parameter k: @Share must be a LocalRef<S>, not java.lang.Integer");
    }

    @Test
    void alwaysHandlersKeepTheirClassToThemselves() {
        assertError(hooks("""
                @Inject(method = "compute", at = @At("FINALLY"), always = true)
                public static void a(CallbackInfoReturnable<Integer> ci) {
                }

                @Inject(method = "compute", at = @At("HEAD"))
                public static void b(CallbackInfoReturnable<Integer> ci) {
                }
                """), "H.a: always = true handlers must be the only handlers of their hook class on"
                + " t.Target.compute(I)I, but H.b is not always");
    }

    @Test
    void gateNeedsAnInstance() {
        Compiler.Result result = compile("""
                @Hooks(value = Target.class, gate = H.Gate.class)
                public final class H {
                    public static final class Gate implements HookGate {
                        public boolean active() {
                            return true;
                        }

                        public void failed(String what, Throwable t) {
                        }
                    }

                    @Inject(method = "compute", at = @At("HEAD"))
                    public static void h(CallbackInfoReturnable<Integer> ci) {
                    }
                }
                """);
        assertError(result, "h.H: the gate h.H.Gate must be a public HookGate with a public static final INSTANCE");
    }

    @Test
    void namedGateIsUsedAndListed() {
        Compiler.Result result = compile("""
                @Hooks(value = Target.class, gate = H.Gate.class)
                public final class H {
                    public static final class Gate implements HookGate {
                        public static final Gate INSTANCE = new Gate();

                        public boolean active() {
                            return true;
                        }

                        public void failed(String what, Throwable t) {
                        }
                    }

                    @Inject(method = "compute", at = @At("FINALLY"))
                    public static void h(CallbackInfoReturnable<Integer> ci, @Thrown Throwable t) {
                    }

                    @ModifyReturnValue(method = "compute")
                    public static int m(int original) {
                        return original;
                    }
                }
                """);
        assertCompiles(result);
        String glue = glue(result);
        assertTrue(glue.contains("h0$active = MOD.active(h.H.Gate.INSTANCE);"));
        assertTrue(glue.contains("boolean done = thrown != null;"));
        assertTrue(glue.replaceAll("\\s+", " ").contains("java.lang.Integer returned = null; if (thrown == null) {"
                + " returned = ret; } CallbackInfoReturnable<java.lang.Integer> ci = new CallbackInfoReturnable<>("
                + "\"compute\", false, returned);"));
        assertEquals("""
                zabric-hooks v1
                mod test-mod
                gate h.H$Gate
                patch glue.TestModGlue$Target_compute
                hook t.Target compute (I)I glue.TestModGlue$Target_compute
                """, result.resource("META-INF/zabric/hooks/h.H.txt"));
    }

    // ---- options ----

    @Test
    void optionsAreRequired() {
        Compiler.Result result = Compiler.compile(dir, Map.of(), "t/Target.java", TARGET, "t/Pkg.java", PKG,
                "h/H.java", IMPORTS + """
                        @Hooks(Target.class)
                        public final class H {
                            @Inject(method = "compute", at = @At("HEAD"))
                            public static void h(CallbackInfoReturnable<Integer> ci) {
                            }
                        }
                        """);
        assertError(result, "the annotation processor option zabric.modId is missing");
        assertError(result, "the annotation processor option zabric.javaPkgName is missing");
    }

    @Test
    void glueClassName() {
        assertEquals("MyModGlue", ZabricProcessor.defaultClassName("my-mod"));
        assertEquals("WeaveGlue", ZabricProcessor.defaultClassName("weave"));
        assertEquals("_3dGlue", ZabricProcessor.defaultClassName("3d"));
        Compiler.Result result = Compiler.compile(dir, Map.of("zabric.modId", "m", "zabric.javaPkgName", "glue",
                "zabric.glueClass", "Custom"), "t/Target.java", TARGET, "t/Pkg.java", PKG, "h/H.java", IMPORTS + """
                @Hooks(Target.class)
                public final class H {
                    @Inject(method = "compute", at = @At("HEAD"))
                    public static void h(CallbackInfoReturnable<Integer> ci) {
                    }
                }
                """);
        assertCompiles(result);
        assertTrue(result.source("glue/Custom.java").contains("public final class Custom {"));
        assertNull(result.source("glue/MGlue.java"));
    }
}
