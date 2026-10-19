package g

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.ModifyVariable
import t.Target

@Hooks(Target::class, gate = Gate::class)
object GatedHooks {
  // slot 4: this is 0, s 1, the long gap 2 and 3
  @ModifyVariable(method = ["run"], at = At("HEAD"), argsOnly = true, index = 4)
  fun count(n: Int): Int = n + 1
}
