package com.dont39.pz.modsync.client

import com.dont39.pz.common.put
import com.dont39.pz.modsync.client.JoinSession.Phase
import se.krka.kahlua.vm.KahluaTable
import zombie.Lua.LuaManager

/**
 * The table `Dont39AllInOne_getModSyncJoin` returns, read by `Dont39AllInOne_ModSyncJoin.lua`; change both together.
 * Numbers are Lua doubles. `state` is "preparing", "confirm", "download" or "restart".
 * - confirm: `server` ("<host>:<port>"), `files` and `bytes` to download, and `items`, an array of tables with `name`,
 *   `files`, `bytes` and `java` (true when its jars change) of each item that downloads anything or changes a jar.
 * - download: `files`, `bytes` (totals), `filesDone`, `bytesDone`.
 * - restart: the join stopped for a restart of the game; `stop` counts such stops, so a screen can tell them apart,
 *   `reason` is the stop's text, and `relaunch` is true when `Dont39AllInOne_restartModSyncGame` can restart the game.
 */
internal object JoinStatus {
  fun restart(stop: Int, reason: String, relaunch: Boolean): KahluaTable = LuaManager.platform.newTable().apply {
    rawset("state", "restart")
    put("stop", stop)
    rawset("reason", reason)
    rawset("relaunch", relaunch)
  }

  /** Null while the join has not reached its mod check, or no longer waits. */
  fun table(session: JoinSession): KahluaTable? {
    val table = LuaManager.platform.newTable()
    when (val phase = session.phase) {
      Phase.Start, Phase.Over -> return null
      is Phase.Preparing -> table.rawset("state", "preparing")
      is Phase.Confirm -> {
        val prepared = phase.prepared
        table.rawset("state", "confirm")
        table.rawset("server", "${session.host}:${session.gamePort}")
        table.put("files", prepared.downloadFiles)
        table.put("bytes", prepared.downloadBytes)
        val items = LuaManager.platform.newTable()
        prepared.listed.forEachIndexed { i, w ->
          val plan = (w as? JoinSession.Work.Build)?.plan
          val item = LuaManager.platform.newTable()
          item.rawset("name", w.marked.label)
          item.put("files", plan?.download?.size ?: 0)
          item.put("bytes", plan?.downloadBytes ?: 0)
          item.rawset("java", w.jarChanges.isNotEmpty())
          items.rawset((i + 1).toDouble(), item)
        }
        table.rawset("items", items)
      }
      is Phase.Building -> {
        val progress = phase.worker.progress
        table.rawset("state", "download")
        table.put("files", progress.totalFiles)
        table.put("bytes", progress.totalBytes)
        table.put("filesDone", progress.filesDone)
        table.put("bytesDone", progress.bytesDone)
      }
    }
    return table
  }
}
