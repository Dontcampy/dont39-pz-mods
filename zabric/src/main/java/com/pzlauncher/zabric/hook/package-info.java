/**
 * Zabric's hook API: annotations an author puts on a hook class, and the small runtime the generated glue calls.
 * <p>
 * A hook class is annotated with {@link com.pzlauncher.zabric.hook.Hooks}; its static handlers carry
 * {@link com.pzlauncher.zabric.hook.Inject}, {@link com.pzlauncher.zabric.hook.ModifyReturnValue} or
 * {@link com.pzlauncher.zabric.hook.ModifyVariable}. The names and their meaning follow Mixin and MixinExtras where
 * they match; what this API does not support is a compile error, never a silent no-op. Zabric's annotation processor
 * ({@code com.pzlauncher:zabric-processor}) checks the hook classes against the compile classpath and generates the
 * loader glue into the mod's jar.
 * <p>
 * At run time the glue runs a handler only while its mod's hooks are open ({@link com.pzlauncher.zabric.hook.ModHooks})
 * and its gate is active ({@link com.pzlauncher.zabric.hook.HookGate}). Whatever a handler throws goes to its gate and
 * never into the hooked method, which then runs as without the handler. A hook the loader did not weave into its target
 * method goes to its gate the same way: Zabric checks each hooked method when its mod enters the mod list and when the
 * target class loads.
 * <p>
 * Order: handlers on one method from one compilation run hook class by hook class (by qualified name), each in
 * declaration order. Across mods the loader's mod order decides; there is no priority.
 */
package com.pzlauncher.zabric.hook;
