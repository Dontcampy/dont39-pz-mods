package com.dont39.pz.modsync.client

import com.dont39.pz.common.put
import com.dont39.pz.common.serverKey
import com.dont39.pz.modsync.ModSync
import com.dont39.pz.modsync.client.rejoin.RejoinStore
import com.dont39.pz.zbktutils.glue.LuaGlobal
import java.lang.management.ManagementFactory
import se.krka.kahlua.vm.KahluaTable
import zombie.Lua.LuaManager
import zombie.network.GameClient

/**
 * The main menu's offer to rejoin the server a join stopped for a restart, for `Dont39AllInOne_ModSyncRejoin.lua`.
 * On the game thread.
 */
internal object RejoinOffer {
  private val launchStart = ManagementFactory.getRuntimeMXBean().startTime

  // the main menu's event also fires on every reload of the mods: offered once per launch
  private var offered = false

  private val store by lazy { RejoinStore(ModSyncClient.cache.root) }

  /** When a join to [host]:[port] stops for a restart. */
  @Synchronized
  fun record(host: String, port: Int) {
    val user = checkNotNull(GameClient.username) { "the join has no user name" }
    val name = GameClient.serverName?.takeIf { it.isNotBlank() } ?: serverKey(host, port)
    store.write(RejoinStore.Rejoin(host, port, user, name, launchStart))
    ModSync.log { "the main menu offers to rejoin $name after the restart" }
  }

  /**
   * The server to offer, once per launch and never in the launch that stopped for it: a table with `host`, `port`,
   * `user` (the account's user name) and `name` (the server list's name); nil when there is none.
   */
  @LuaGlobal("Dont39AllInOne_getModSyncRejoin")
  @Synchronized
  fun luaOffer(): KahluaTable? {
    if (!ModSync.active()) return null
    return try {
      // a join loading the server's mods reloads the main menu's Lua too
      if (offered || GameClient.connection != null) return null
      val rejoin = store.read() ?: return null
      if (rejoin.written == launchStart) return null
      offered = true
      ModSync.log { "offering to rejoin ${rejoin.name} (${serverKey(rejoin.host, rejoin.port)}) as ${rejoin.user}" }
      LuaManager.platform.newTable().apply {
        rawset("host", rejoin.host)
        put("port", rejoin.port)
        rawset("user", rejoin.user)
        rawset("name", rejoin.name)
      }
    } catch (t: Throwable) {
      ModSync.disable("reading the server to rejoin", t)
      null
    }
  }

  /** The offer was answered, either way. */
  @LuaGlobal("Dont39AllInOne_dropModSyncRejoin")
  @Synchronized
  fun luaDrop(answer: String?) {
    if (!ModSync.active()) return
    try {
      store.drop()
      ModSync.log { "rejoin offer: $answer" }
    } catch (t: Throwable) {
      ModSync.disable("dropping the server to rejoin", t)
    }
  }
}
