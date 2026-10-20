package com.dont39.pz.modsync

import com.dont39.pz.modsync.JoinSession.Answer
import com.dont39.pz.modsync.JoinSession.Step
import com.dont39.pz.zbktutils.glue.LuaGlobal
import java.nio.file.Path
import se.krka.kahlua.vm.KahluaTable
import zombie.Lua.LuaEventManager
import zombie.ZomboidFileSystem
import zombie.core.Translator
import zombie.core.network.ByteBufferReader
import zombie.core.znet.SteamWorkshop
import zombie.gameStates.ConnectToServerState
import zombie.gameStates.MainScreenState
import zombie.network.GameClient

/**
 * The client's side of a join: the session from the trailer, the Workshop items it takes over, the `CheckMods` hold
 * and when the swap ends. On the game thread, where vanilla runs the join and the main menu's Lua.
 */
internal object ModSyncClient {
  private const val VERSION_MISMATCH = "VersionMismatch"
  private const val DISCONNECT_REASON = "connect-dont39-modsync"

  private var session: JoinSession? = null

  // set when the session's mods are loaded, so the join's end no longer turns the swap off
  private var loaded = false

  // the Lua disabled itself, which is final for the game session: no later confirm gets an answer
  private var dialogFailed = false

  private val cache by lazy {
    Cache(Path.of(ZomboidFileSystem.instance.cacheDir, "Dont39AllInOne", "ModSync"))
  }

  /** At `ConnectionDetails.parse` HEAD, before the join reads [details]. */
  @Synchronized
  fun joinStarts(details: ByteBufferReader?) {
    endSession()
    Swap.off("a new join started")
    if (!ModSync.active()) {
      return
    }
    checkNotNull(details) { "the join's connection-details are null" }
    val target = "${GameClient.ip}:${GameClient.port}"
    // a join by Steam ID has a Steam ID in ip and no port
    val host = if (GameClient.port == 0) null else GameClient.ip?.trim()
    when (val location = Trailer.location(details.bb, host)) {
      is Trailer.Location.None -> ModSync.log { "join to $target: no mod sync, as ${location.reason}" }
      is Trailer.Location.At -> {
        session = JoinSession(
          checkNotNull(host),
          GameClient.port,
          location.base,
          cache,
          // looked up when used: a client without Steam has no instance, and no Workshop step to take items from
          { id -> SteamWorkshop.instance.GetItemInstallFolder(id) },
        ) { ModSync.log(it) }
        ModSync.log { "join to $target: the server offers its Workshop items at ${location.base}" }
      }
    }
  }

  /** At `WorkshopItem.setState` HEAD, for a Workshop item that failed: true when this join takes it from the server. */
  @Synchronized
  fun takeOver(item: ConnectToServerState.WorkshopItem): Boolean {
    val s = session ?: return false
    if (item.error != VERSION_MISMATCH) {
      return false
    }
    val marked = JoinSession.Marked(item.id, item.serverTimeStamp, item.details?.title)
    s.mark(marked)
    item.error = null
    ModSync.log {
      "${marked.label}: your copy is another version than the server's (${marked.timestamp}); taking the server's"
    }
    return true
  }

  /**
   * At `CheckMods` HEAD: true holds the join for this frame, false lets vanilla check the mods. Catches everything: an
   * error would otherwise let the join go on with the client's own copy of a taken item, which the player did not
   * choose.
   */
  @Synchronized
  fun hold(join: ConnectToServerState): Boolean {
    val s = session ?: return false
    if (!s.hasMarks) {
      return false
    }
    try {
      // Back, or the connection was lost: vanilla keeps polling the join and shows its own reason
      if (GameClient.connection == null) {
        ModSync.log { "the join ended while it waited for the server's Workshop items" }
        end(join)
        return true
      }
      if (!ModSync.active()) {
        stop(join, "Failed_Inactive", listOf(checkNotNull(ModSync.whyNotActive())), "the feature is off")
        return true
      }
      if (dialogFailed && s.phase is JoinSession.Phase.Confirm) {
        s.answer(Answer.DIALOG_FAILED)
      }
      return when (val step = s.poll()) {
        Step.Hold -> true
        is Step.Ready -> {
          session = null
          loaded = false
          Swap.on(step.entries)
          false
        }
        is Step.Stop -> {
          stop(join, step.key, step.args, step.why)
          true
        }
        is Step.Broken -> {
          ModSync.disable(step.what, step.error)
          stop(join, "Failed_Error", emptyList(), "an error")
          true
        }
      }
    } catch (t: Throwable) {
      ModSync.disable("holding the join for the server's Workshop items", t)
      stop(join, "Failed_Error", emptyList(), "an error")
      return true
    }
  }

  /** At `Core.ResetLua(String, String)` HEAD. */
  @Synchronized
  fun resetLua(activeMods: String?) {
    // only receiveServerOptions loads "client": the session's mods, from the swapped folders
    if (activeMods == "client") {
      loaded = true
    } else {
      Swap.off("the game reloads its mods ($activeMods)")
    }
  }

  /** At `IngameState.exit` HEAD: the game reloads the main menu's mods without `ResetLua`. */
  @Synchronized
  fun leftGame() {
    Swap.off("the game ended")
  }

  /** At `ConnectToServerState.exit` HEAD. */
  @Synchronized
  fun joinEnded() {
    endSession()
    if (!loaded) {
      Swap.off("the join ended before the server's mods were loaded")
    }
  }

  /**
   * For `Dont39AllInOne_ModSyncJoin.lua`: what the Connect screen shows while the join waits; nil when it does not
   * wait for this feature. Keys in [JoinStatus].
   */
  @LuaGlobal("Dont39AllInOne_getModSyncJoin")
  @Synchronized
  fun luaStatus(): KahluaTable? {
    if (!ModSync.active()) return null
    return try {
      session?.let(JoinStatus::table)
    } catch (t: Throwable) {
      ModSync.disable("reading the join's status", t)
      null
    }
  }

  /** The dialog's answer: "accept", "remember" (accept, and do not ask again for this server), "decline", "failed". */
  @LuaGlobal("Dont39AllInOne_answerModSyncJoin")
  @Synchronized
  fun luaAnswer(answer: String?) {
    if (!ModSync.active()) return
    try {
      val a = when (answer) {
        "accept" -> Answer.ACCEPT
        "remember" -> Answer.REMEMBER
        "decline" -> Answer.DECLINE
        "failed" -> Answer.DIALOG_FAILED
        else -> throw IllegalArgumentException("unknown answer \"$answer\"")
      }
      if (a == Answer.DIALOG_FAILED) {
        dialogFailed = true
      }
      val s = session
      // a click in the moment before the next poll closes the dialog, after Back or a lost connection ended the join
      if (s == null || s.phase !is JoinSession.Phase.Confirm) {
        if (a != Answer.DIALOG_FAILED) {
          ModSync.log { "the dialog answered \"$answer\" after the join stopped asking; ignored" }
        }
        return
      }
      s.answer(a)
    } catch (t: Throwable) {
      // the hold sees the feature off and stops the join
      ModSync.disable("taking the dialog's answer", t)
    }
  }

  private fun endSession() {
    session?.cancel()
    session = null
  }

  /** Ends [join] as vanilla's `Exit` state does, a frame earlier: the game keeps its `State` enum from widening. */
  private fun end(join: ConnectToServerState) {
    MainScreenState.getInstance().setConnectToServerState(null)
    // runs the exit hook: the session ends, and the swap is off
    join.exit()
  }

  /** Ends the join as vanilla's mod check does: the Connect screen shows [key]'s text and a Back button. */
  private fun stop(join: ConnectToServerState, key: String, args: List<String>, why: String) {
    // first, so the join ends even if the rest fails
    end(join)
    ModSync.log { "stopped the join: $why" }
    try {
      Swap.off("the join stopped")
      val text = Translator.getText(JoinSession.KEY_PREFIX + key, *args.toTypedArray())
      LuaEventManager.triggerEvent("OnConnectFailed", text)
      GameClient.connection?.forceDisconnect(DISCONNECT_REASON)
    } catch (t: Throwable) {
      ModSync.disable("stopping the join", t)
    }
  }
}
