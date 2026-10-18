package g

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import t.Target

@Hooks(Target::class)
object AlwaysHooks {
  @Inject(method = ["run"], at = [At("HEAD")], always = true)
  fun running(s: String?, gap: Long, n: Int, ci: CallbackInfo) {}
}
