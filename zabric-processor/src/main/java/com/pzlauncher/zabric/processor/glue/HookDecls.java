package com.pzlauncher.zabric.processor.glue;

import java.util.List;

/**
 * Hook classes as a front end reads them: annotation values as written, targets resolved on the compile classpath.
 * {@code node} fields are the front end's elements, passed back to its {@link Reporter}.
 */
public final class HookDecls {
    private HookDecls() {
    }

    /** {@code callee}: the Java expression handlers are called on; {@code gate}: null for the default gate. */
    public record HookClass<T>(Object node, String sourceName, String binaryName, String simpleName, String callee,
                               Gate gate, List<TargetClass<T>> targets, List<Handler<T>> handlers) {
    }

    /** A named gate; the glue reads its {@code INSTANCE}. */
    public record Gate(String sourceName, String binaryName) {
    }

    /** {@code members}: in declaration order, including compiler bridges (the loader matches those too). */
    public record TargetClass<T>(String binaryName, T type, List<Member<T>> members) {
    }

    /** A constructor is named {@code <init>}. */
    public record Member<T>(String name, List<T> params, T returnType, boolean isStatic, boolean hasBody,
                            boolean bridge) {
    }

    public enum Kind {
        INJECT("@Inject"), MODIFY_RETURN_VALUE("@ModifyReturnValue"), MODIFY_VARIABLE("@ModifyVariable");

        public final String annotation;

        Kind(String annotation) {
            this.annotation = annotation;
        }
    }

    /** Attributes its annotation lacks take their defaults ({@code false}, {@code -1}, {@code argsOnly} true). */
    public record Handler<T>(Object node, String name, Kind kind, List<String> method, List<String> at,
                             boolean cancellable, boolean always, boolean argsOnly, int index, int ordinal,
                             List<Param<T>> params, T returnType) {
    }

    /** {@code share}: the {@code @Share} key, or null. */
    public record Param<T>(String name, T type, boolean self, String share, boolean thrown) {
    }
}
