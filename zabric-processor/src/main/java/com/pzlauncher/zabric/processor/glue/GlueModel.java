package com.pzlauncher.zabric.processor.glue;

import com.pzlauncher.zabric.processor.glue.HookDecls.Kind;

import java.util.List;

/** The checked hooks of one compilation, as {@link GlueWriter} writes them. Types are Java source. */
public final class GlueModel {
    private GlueModel() {
    }

    /** {@code className}: the outer glue class's simple name, in {@code packageName}. */
    public record Unit(String modId, String packageName, String className, List<Patch> patches,
                       List<Listing> listings) {
        public String qualifiedName() {
            return packageName + "." + className;
        }

        /** The binary name of a patch class, as the listing names it. */
        public String binaryName(Patch patch) {
            return qualifiedName() + "$" + patch.name();
        }
    }

    /** One loader patch class: every handler of the compilation on one target method. */
    public record Patch(String name, Target target, List<Block> blocks) {
    }

    /**
     * A target method. {@code owner}: binary name; {@code ownerSource}: canonical name. {@code returnType}: erased,
     * {@code void} for none (and constructors).
     */
    public record Target(String owner, String ownerSource, String method, String descriptor, List<Arg> args,
                         String returnType) {
        public boolean isVoid() {
            return returnType.equals("void");
        }
    }

    /**
     * A target argument: its erased type and the type the glue binds it as ({@code java.lang.Object} for an array the
     * loader's matcher could not match by type); {@code writable} when a handler modifies it.
     */
    public record Arg(String type, String binding, boolean writable) {
    }

    /**
     * The handlers of one hook class on the target ({@code hN} in the glue). {@code gate}: the gate as a Java
     * expression; {@code always}: all its handlers here are {@code always}.
     */
    public record Block(String hookClass, String callee, String gate, boolean always, List<Share> shares,
                        List<Call> calls) {
    }

    /** A {@code @Share} key and the type argument of its {@code LocalRef}. */
    public record Share(String key, String type) {
    }

    public enum At {
        HEAD, RETURN, FINALLY
    }

    /**
     * One handler call. {@code returnable}: the type argument of its CallbackInfoReturnable, null for CallbackInfo
     * (and for the modify kinds); {@code modifiedArg}: the argument a {@code @ModifyVariable} replaces, else -1.
     */
    public record Call(String handler, Kind kind, At at, boolean cancellable, String returnable, int modifiedArg,
                       List<Value> values) {
        public boolean exits() {
            return at != At.HEAD;
        }

        public boolean uses(Value.Kind kind) {
            return values.stream().anyMatch(v -> v.kind() == kind);
        }
    }

    /** A handler argument: {@code index} for {@link Kind#ARG}, {@code key} for {@link Kind#SHARE}. */
    public record Value(Kind kind, int index, String key) {
        public enum Kind {
            SELF, ARG, CALLBACK, THROWN, SHARE, ORIGINAL
        }

        public static Value of(Kind kind) {
            return new Value(kind, -1, null);
        }
    }

    /** The listing of one hook class; {@code gate}: a binary name or {@code default}. */
    public record Listing(String hookClass, String gate, List<Patch> patches) {
    }
}
