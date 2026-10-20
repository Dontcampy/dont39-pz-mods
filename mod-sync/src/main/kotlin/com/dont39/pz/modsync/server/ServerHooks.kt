package com.dont39.pz.modsync.server

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import zombie.core.network.ByteBufferWriter
import zombie.core.raknet.UdpConnection
import zombie.network.ConnectionDetails
import zombie.network.GameServer
import zombie.network.ServerWorldDatabase

@Hooks(GameServer::class)
internal object ServerStart {
  // the save's sandbox options and the Workshop install are done, and no connection-details was written yet
  @Inject(method = ["startServer"], at = [At("RETURN")])
  fun started(ci: CallbackInfo) {
    ModSyncServer.start()
  }
}

/** The server's end of the location trailer; the client's is `DetailsParse`. */
@Hooks(ConnectionDetails::class)
internal object DetailsWrite {
  @Inject(method = ["write"], at = [At("RETURN")])
  fun written(
    connection: UdpConnection?,
    logon: ServerWorldDatabase.LogonResult?,
    out: ByteBufferWriter?,
    ci: CallbackInfo,
  ) {
    ModSyncServer.appendTrailer(checkNotNull(out) { "ConnectionDetails.write wrote to no buffer" })
  }
}
