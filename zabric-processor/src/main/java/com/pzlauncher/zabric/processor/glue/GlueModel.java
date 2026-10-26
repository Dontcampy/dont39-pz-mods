package com.pzlauncher.zabric.processor.glue;

import com.pzlauncher.zabric.processor.glue.HookDecls.Kind;

import java.util.List;

/** The checked hooks of one compilation, as {@link GlueWriter} writes them. Types are Java source. */
public final class GlueModel {
    private GlueModel() {
    }

    public record Unit(String modId, String packageName, String className, List<Patch> patches,
                       List<Listing> listings) {
        public String qualifiedName() {
            return packageName + "." + className;
        }

        public String binaryName(Patch patch) {
            return qualifiedName() + "$" + patch.name();
        }
    }

    /**
     * One loader patch class: every handler of the compilation on one target method. {@code positional}: the arguments
     * are bound without {@code @Argument}, so the loader applies it to methods of exactly that many parameters only.
     */
    public record Patch(String name, Target target, boolean positional, List<Block> blocks) {
    }

    /** {@code owner}: binary name; {@code ownerSource}: canonical name; {@code returnType}: erased. */
    public record Target(String owner, String ownerSource, String method, String descriptor, List<Arg> args,
                         String returnType) {
        public boolean isVoid() {
            return returnType.equals("void");
        }
    }

    /** {@code binding}: {@code java.lang.Object} for an array, which the loader's matcher cannot match by type. */
    public record Arg(String type, String binding, boolean writable) {
    }

    /** The handlers of one hook class on the target; {@code gate} is a Java expression. */
    public record Block(String hookClass, String callee, String gate, boolean always, List<Share> shares,
                        List<Call> calls) {
    }

    /** {@code type}: the type argument of the {@code LocalRef}. */
    public record Share(String key, String type) {
    }

    public enum At {
        HEAD, RETURN, FINALLY
    }

    /** {@code returnable}: CallbackInfoReturnable's type argument, else null; {@code modifiedArg}: -1 for none. */
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
