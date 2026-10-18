package com.dont39.pz.tunnel

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import zombie.network.GameServer

@Hooks(GameServer::class)
internal object ServerStart {
  // the save's sandbox options are loaded by now, and RakNet has just started accepting players
  @Inject(method = ["startServer"], at = [At("RETURN")])
  fun started(ci: CallbackInfo) {
    TunnelServer.start()
  }
}
