package com.pzlauncher.zabric.processor.glue;

import java.util.List;

/**
 * Hook classes as a front end read them: annotation values as written, targets resolved against the compile
 * classpath. {@code node} fields are the front end's elements, passed back to its {@link Reporter}.
 */
public final class HookDecls {
    private HookDecls() {
    }

    /**
     * A {@code @Hooks} class. {@code callee}: the Java expression its handlers are called on (the class's canonical
     * name, or a Kotlin object's {@code INSTANCE}). {@code gate}: null for the mod's default gate.
     */
    public record HookClass<T>(Object node, String sourceName, String binaryName, String simpleName, String callee,
                               Gate gate, List<TargetClass<T>> targets, List<Handler<T>> handlers) {
    }

    /** A named gate; the glue reads its {@code INSTANCE}. */
    public record Gate(String sourceName, String binaryName) {
    }

    /**
     * A target class. {@code members}: every method and constructor the class declares, including the bridge methods
     * the compiler adds (the loader matches those too), in declaration order.
     */
    public record TargetClass<T>(String binaryName, T type, List<Member<T>> members) {
    }

    /** A declared method, or a constructor named {@code <init>}. {@code hasBody}: neither abstract nor native. */
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

    /**
     * A handler. {@code at}: the {@code @At} values as written. Attributes the handler's annotation does not have are
     * passed as their defaults ({@code false}, {@code -1}); {@code argsOnly} is true for every kind but
     * {@code @ModifyVariable}.
     */
    public record Handler<T>(Object node, String name, Kind kind, List<String> method, List<String> at,
                             boolean cancellable, boolean always, boolean argsOnly, int index, int ordinal,
                             List<Param<T>> params, T returnType) {
    }

    /** A handler parameter; {@code share}: the {@code @Share} key, or null. */
    public record Param<T>(String name, T type, boolean self, String share, boolean thrown) {
    }
}
