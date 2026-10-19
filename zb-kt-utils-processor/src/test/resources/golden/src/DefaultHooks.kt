package g

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.CallbackInfoReturnable
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import com.pzlauncher.zabric.hook.LocalRef
import com.pzlauncher.zabric.hook.ModifyReturnValue
import com.pzlauncher.zabric.hook.Self
import com.pzlauncher.zabric.hook.Share
import com.pzlauncher.zabric.hook.Thrown
import t.Target

@Hooks(Target::class)
internal object DefaultHooks {
  @Inject(method = ["<init>"], at = [At("RETURN")])
  fun created(@Self self: Target, name: String?, ci: CallbackInfo) {}

  @Inject(method = ["compute"], at = [At("HEAD")], cancellable = true)
  fun before(@Self self: Target, x: Int, ci: CallbackInfoReturnable<Int>, @Share("start") start: LocalRef<Long>) {}

  @Inject(method = ["compute"], at = [At("FINALLY")])
  fun after(ci: CallbackInfoReturnable<Int>, @Thrown thrown: Throwable?, @Share("start") start: LocalRef<Long>) {}

  @ModifyReturnValue(method = ["label"])
  fun tagged(original: String?, @Self self: Target, suffix: String?): String? = original

  @Inject(method = ["check"], at = [At("HEAD")])
  fun checking(parts: Array<String?>?, ci: CallbackInfoReturnable<Boolean>) {}
}
