package com.pzlauncher.zabric.processor.glue;

import java.util.List;

/**
 * ZombieBuddy's choice of the methods a patch class applies to, for the glue this processor writes
 * ({@code PatchEngine.applyPatches}, the signature-aware matcher with {@code strictMatch = true}). Every advice method
 * of the glue binds all target arguments, so each contributes a complete argument map; one without arguments has only
 * special parameters (Local, This, Return, Thrown).
 */
final class LoaderMatcher {
    private LoaderMatcher() {
    }

    /**
     * Whether ZombieBuddy applies the patch to a method of the right name with {@code params}. {@code maps}: per advice
     * method that binds arguments, the type ZombieBuddy compares each with (an array parameter's component type;
     * null for {@code java.lang.Object}, which it does not compare). {@code noParam}: an advice method binds none.
     */
    static <T> boolean selects(TypeSystem<T> types, List<T> params, List<List<T>> maps, boolean noParam) {
        int count = params.size();
        if (noParam && count == 0) {
            return true;
        }
        if (noParam && maps.isEmpty()) {
            return false;
        }
        int minParams = maps.stream().mapToInt(List::size).max().orElse(0);
        for (List<T> map : maps) {
            // not exact: @Argument means "at least this many parameters"
            if (count < map.size() || count < minParams) {
                continue;
            }
            boolean all = true;
            for (int i = 0; i < map.size() && all; i++) {
                T expected = map.get(i);
                if (expected == null || types.erasedName(expected).equals(Names.OBJECT)) {
                    continue;
                }
                all = types.isSubtype(params.get(i), expected);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }
}
