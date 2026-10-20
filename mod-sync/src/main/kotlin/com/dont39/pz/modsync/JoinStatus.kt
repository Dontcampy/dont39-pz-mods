package com.dont39.pz.modsync

import com.dont39.pz.modsync.JoinSession.Phase
import se.krka.kahlua.vm.KahluaTable
import zombie.Lua.LuaManager

/**
 * The table `Dont39AllInOne_getModSyncJoin` returns, read by `Dont39AllInOne_ModSyncJoin.lua`; change both together.
 * Numbers are Lua doubles. `state` is "preparing", "confirm" or "download".
 * - confirm: `server` ("<host>:<port>"), `files` and `bytes` to download, and `items`, an array of tables with `name`,
 *   `files` and `bytes` of each item that downloads anything.
 * - download: `files`, `bytes` (totals), `filesDone`, `bytesDone`.
 */
internal object JoinStatus {
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
        prepared.downloads.forEachIndexed { i, w ->
          val plan = checkNotNull(w.plan)
          val item = LuaManager.platform.newTable()
          item.rawset("name", w.marked.label)
          item.put("files", plan.download.size)
          item.put("bytes", plan.downloadBytes)
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

  private fun KahluaTable.put(key: String, value: Number) = rawset(key, value.toDouble())
}
