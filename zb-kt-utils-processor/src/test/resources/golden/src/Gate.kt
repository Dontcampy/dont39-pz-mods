package g

import com.pzlauncher.zabric.hook.HookGate

object Gate : HookGate {
  override fun active() = true

  override fun failed(what: String, t: Throwable) {}
}
