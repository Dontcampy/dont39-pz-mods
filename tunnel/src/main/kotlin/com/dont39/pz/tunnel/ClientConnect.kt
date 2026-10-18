package com.dont39.pz.tunnel

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import com.pzlauncher.zabric.hook.Self
import zombie.core.raknet.UdpEngine

/**
 * Takes over a join to an address with a tunnel: connects RakNet to the tunnel's local end, or stops the join. Only the
 * connect call changes; the game's own fields keep the typed address, which also names the save folder.
 */
@Hooks(UdpEngine::class)
internal object ClientConnect {
  @Inject(method = ["Connect"], at = [At("HEAD")], cancellable = true)
  fun connect(
    @Self engine: UdpEngine,
    host: String?,
    port: Int,
    password: String?,
    useSteamRelay: Boolean,
    ci: CallbackInfo,
  ) {
    if (TunnelClient.connect(engine, host, port, password, useSteamRelay)) {
      ci.cancel()
    }
  }

  @Inject(method = ["Shutdown"], at = [At("HEAD")])
  fun shutdown(@Self engine: UdpEngine, ci: CallbackInfo) {
    TunnelClient.shutdown(engine)
  }
}
