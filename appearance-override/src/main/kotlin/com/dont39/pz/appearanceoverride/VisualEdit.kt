package com.dont39.pz.appearanceoverride

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import com.pzlauncher.zabric.hook.Self
import zombie.characters.IsoPlayer
import zombie.characters.WornItems.WornItems
import zombie.characters.animals.IsoAnimal
import zombie.core.skinnedmodel.visual.ItemVisuals
import zombie.network.GameServer

/**
 * Inside a render scope, the visual list handed out loses its hidden items and gets each look's visual in its item's
 * place. The list is the caller's; the items' own visuals stay as they are.
 */
internal object VisualEdit {
  // debug output: the last edit each consumer made for each subject
  private val logged = HashMap<String, String>()

  /** Local players, single player, corpses, mannequins and descs: their worn items. */
  fun worn(list: ItemVisuals?) {
    val frame = RenderScope.current() ?: return
    edit(frame, frame.player, checkNotNull(list) { "WornItems.getItemVisuals was given no list" })
  }

  /** Other players on a client: the visuals the game's packets gave them. */
  fun remote(player: IsoPlayer, list: ItemVisuals?) {
    // the other branch went through WornItems.getItemVisuals, edited there
    if (!player.remote || GameServer.server || player is IsoAnimal) return
    val frame = RenderScope.current() ?: return
    edit(frame, player, checkNotNull(list) { "IsoPlayer.getItemVisuals was given no list" })
  }

  private fun edit(frame: RenderScope.Frame, player: IsoPlayer?, list: ItemVisuals) {
    val held = if (player != null) ClientSets.held(player) ?: return else null
    val changes = if (AppearanceOverride.debug) mutableListOf<String>() else null
    for (i in list.indices.reversed()) {
      val worn = list[i] ?: continue
      val drawn = if (held != null) held.drawn(worn) else ItemOverrides.drawn(worn)
      when (drawn) {
        null -> continue
        Drawn.Hidden -> list.removeAt(i)
        is Drawn.As -> list[i] = Looks.dress(drawn.visual, worn)
      }
      changes?.add(if (drawn is Drawn.As) "${worn.itemType} as ${drawn.visual.itemType}" else "${worn.itemType} hidden")
    }
    if (changes != null) {
      report(frame, player, changes)
    }
  }

  private fun report(frame: RenderScope.Frame, player: IsoPlayer?, changes: List<String>) {
    val subject = "${frame.consumer} of ${player?.let(ClientSets::keyOf) ?: "items"}"
    val text = if (changes.isEmpty()) "nothing" else changes.joinToString()
    val changed = synchronized(logged) { logged.put(subject, text) != text }
    if (changed) {
      AppearanceOverride.log { "debug: $subject: $text" }
    }
  }
}

@Hooks(WornItems::class)
internal object WornItemVisuals {
  @Inject(method = ["getItemVisuals"], at = [At("RETURN")])
  fun read(list: ItemVisuals?, ci: CallbackInfo) = VisualEdit.worn(list)
}

@Hooks(IsoPlayer::class)
internal object PlayerVisuals {
  @Inject(method = ["getItemVisuals(Lzombie/core/skinnedmodel/visual/ItemVisuals;)V"], at = [At("RETURN")])
  fun read(@Self player: IsoPlayer, list: ItemVisuals?, ci: CallbackInfo) = VisualEdit.remote(player, list)
}
