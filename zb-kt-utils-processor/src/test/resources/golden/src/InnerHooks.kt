package g

import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.ModifyReturnValue

@Hooks(targets = ["t.Target\$Inner"])
object InnerHooks {
  @ModifyReturnValue(method = ["items"])
  fun items(original: List<String?>?): List<String?>? = original
}
