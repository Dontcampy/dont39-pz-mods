package com.pzlauncher.zabric.processor.glue;

import java.util.List;

/**
 * Mirrors ZombieBuddy's signature-aware matcher ({@code PatchEngine.applyPatches}, {@code strictMatch = true}); valid
 * only because each advice of the glue binds all target arguments or none.
 */
final class LoaderMatcher {
    private LoaderMatcher() {
    }

    /**
     * {@code maps}: per advice that binds arguments, the types ZombieBuddy compares them with (null: not compared);
     * {@code noParam}: some advice binds none; {@code exact}: the advice binds them without {@code @Argument}.
     */
    static <T> boolean selects(TypeSystem<T> types, List<T> params, List<List<T>> maps, boolean noParam,
                               boolean exact) {
        int count = params.size();
        if (noParam && count == 0) {
            return true;
        }
        if (noParam && maps.isEmpty()) {
            return false;
        }
        int minParams = exact ? 0 : maps.stream().mapToInt(List::size).max().orElse(0);
        for (List<T> map : maps) {
            // @Argument means "at least this many parameters"
            if (exact ? count != map.size() : count < map.size() || count < minParams) {
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
