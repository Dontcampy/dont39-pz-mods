package com.dont39.pz.modsync

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.CallbackInfoReturnable
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import java.nio.file.Path
import zombie.ZomboidFileSystem
import zombie.gameStates.ChooseGameInfo

/**
 * The folder swap: while on, the game finds each marked item's mods in its tree instead of the client's own copy.
 * Turned on and off on the game thread; read wherever the game builds its folder list.
 */
internal object Swap {
  private class State(val entries: List<SwapEntry>) {
    val byInstall: Map<Path, SwapEntry> = entries.associateBy { it.installMods }
  }

  // immutable, as the folder list may be built off the game thread
  @Volatile
  private var state: State? = null

  val on: Boolean
    get() = state != null

  // the game's id → folder map may hold folders found under another folder list; ZomboidFileSystem loads before
  // Zabric, so the map cannot be widened and cleared: its answers are ignored until the game's Reset() clears it
  @Volatile
  var idsStale = false
    private set

  /** At `ZomboidFileSystem.Reset` RETURN: the id → folder map is empty. */
  fun idsCleared() {
    idsStale = false
  }

  /** Throws when the game's folder list does not take the swap; it is then off again. */
  fun on(entries: List<SwapEntry>) {
    check(state == null) { "the folder swap is turned on twice" }
    state = State(entries)
    resetCaches()
    entries.forEach { e -> ModSync.log { "swap on: Workshop item ${e.id} uses ${e.treeMods ?: "no mods"}" } }
    try {
      verify(entries)
    } catch (t: Throwable) {
      off("the folder list did not take it")
      throw t
    }
  }

  fun off(why: String) {
    if (state == null) {
      return
    }
    state = null
    resetCaches()
    ModSync.log { "swap off: $why" }
  }

  /** At `getInstalledItemModsFolders` RETURN: [folders] holds each installed item's `mods`, as absolute paths. */
  fun apply(folders: MutableList<String>) {
    val s = state ?: return
    val swapped = folders.mapNotNull { folder ->
      val entry = s.byInstall[SwapEntry.normalize(folder)]
      if (entry == null) folder else entry.treeMods?.toString()
    }
    folders.clear()
    folders.addAll(swapped)
  }

  // never ZomboidFileSystem.Reset(), which also drops what is loaded
  private fun resetCaches() {
    idsStale = true
    ZomboidFileSystem.instance.resetModFolders()
    ChooseGameInfo.Reset()
  }

  private fun verify(entries: List<SwapEntry>) {
    val folders = ArrayList<String>()
    ZomboidFileSystem.instance.getInstalledItemModsFolders(folders)
    val found = folders.mapTo(hashSetOf()) { SwapEntry.normalize(it) }
    entries.forEach { e ->
      check(e.installMods !in found) { "the game still lists ${e.installMods} of Workshop item ${e.id}" }
      check(e.treeMods == null || SwapEntry.normalize(e.treeMods.toString()) in found) {
        "the game does not list ${e.installMods} of Workshop item ${e.id}, so ${e.treeMods} cannot take its place"
      }
    }
  }
}

@Hooks(ZomboidFileSystem::class)
internal object FolderSwap {
  // always: the list must match what the session loaded until the swap turns off, even after an error
  @Inject(method = ["getInstalledItemModsFolders"], at = [At("RETURN")], always = true)
  fun listed(folders: MutableList<String>?, ci: CallbackInfo) {
    folders?.let(Swap::apply)
  }

  // always, as the list: a stale folder makes getModDetails scan the current list instead; loadMod, which takes a
  // null as "not installed", runs only after Reset()
  @Inject(method = ["getModDir"], at = [At("RETURN")], cancellable = true, always = true)
  fun modDir(ci: CallbackInfoReturnable<String?>) {
    if (Swap.idsStale && ci.returnValue != null) {
      ci.returnValue = null
    }
  }

  // always, as getModDir
  @Inject(method = ["Reset"], at = [At("RETURN")], always = true)
  fun reset(ci: CallbackInfo) {
    Swap.idsCleared()
  }
}
