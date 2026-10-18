package com.dont39.pz.tunnel

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import com.pzlauncher.zabric.hook.ModifyVariable
import com.pzlauncher.zabric.hook.Self
import zombie.core.raknet.UdpEngine

/**
 * Sends a join through the tunnel by changing only the address RakNet connects to; the game's own fields keep the typed
 * address, which also names the save folder.
 */
@Hooks(UdpEngine::class)
internal object ClientConnect {
  @ModifyVariable(method = ["Connect"], at = At("HEAD"), argsOnly = true, ordinal = 0)
  fun host(
    original: String?,
    @Self engine: UdpEngine,
    host: String?,
    port: Int,
    password: String?,
    useSteamRelay: Boolean,
  ): String? = if (TunnelClient.open(engine, host, port)) TunnelClient.LOCAL_HOST else original

  @Inject(method = ["Shutdown"], at = [At("HEAD")])
  fun shutdown(@Self engine: UdpEngine, ci: CallbackInfo) {
    TunnelClient.shutdown(engine)
  }
}
