package com.dont39.pz.serverreborn.network.game

import com.dont39.pz.serverreborn.ServerReborn
import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import com.pzlauncher.zabric.hook.Self
import zombie.core.network.ByteBufferReader
import zombie.network.IConnection
import zombie.network.packets.SyncItemFieldsPacket

/**
 * The game's stale map window: SyncItemFields' parse reloads a map item's annotations without the listener calls its
 * symbols make for every other change, so an open window of that map keeps a list of annotations that are gone and
 * its next edit fails. The game's own sync of an open map does it, and so does a snapshot that repairs one.
 */
@Hooks(SyncItemFieldsPacket::class, gate = ServerReborn::class)
internal object MapSymbolsHooks {
  @Inject(method = ["parse"], at = [At("RETURN")])
  fun parsed(@Self packet: SyncItemFieldsPacket, input: ByteBufferReader?, connection: IConnection?, ci: CallbackInfo) {
    // the game reuses one packet per connection, and a parse that finds no container keeps the map of the one before
    if (packet.containerId.getContainer() == null) return
    // a window rebuilds its list from the symbols at this call
    packet.mapItem?.symbols?.listeners?.forEach { it.onAfterClear() }
  }
}
