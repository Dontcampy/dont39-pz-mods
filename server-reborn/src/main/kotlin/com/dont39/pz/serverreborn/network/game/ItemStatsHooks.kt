package com.dont39.pz.serverreborn.network.game

import com.dont39.pz.serverreborn.ServerReborn
import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import com.pzlauncher.zabric.hook.Self
import zombie.core.network.ByteBufferReader
import zombie.network.IConnection
import zombie.network.packets.ItemStatsPacket

/**
 * The game's stale ItemStats stats, on both sides (processServer applies too): write leaves out a stat at the value
 * below (the fertilized time and wet cooldown of an item that is neither), parse then keeps the field as it was, and
 * the game reuses one packet per connection, so the previous item's value would land on this one.
 */
@Hooks(ItemStatsPacket::class, gate = ServerReborn::class)
internal object ItemStatsHooks {
  @Inject(method = ["parse"], at = [At("HEAD")])
  fun parse(@Self packet: ItemStatsPacket, input: ByteBufferReader?, connection: IConnection?, ci: CallbackInfo) {
    packet.cookingTime = 0f
    packet.minutesToCook = 0f
    packet.minutesToBurn = 0f
    packet.hungChange = 0f
    packet.calories = 0f
    packet.proteins = 0f
    packet.lipids = 0f
    packet.carbohydrates = 0f
    packet.thirstChange = 0f
    packet.fluReduction = 0
    packet.painReduction = 0f
    packet.endChange = 0f
    packet.foodSicknessChange = 0
    packet.stressChange = 0f
    packet.fatigueChange = 0f
    packet.unhappyChange = 0f
    packet.boredomChange = 0f
    packet.poisonPower = 0
    // "none"
    packet.poisonDetectionLevel = -1
    packet.baseHunger = 0f
    packet.fertilizedTime = 0
    packet.wetCooldown = 0f
  }
}
