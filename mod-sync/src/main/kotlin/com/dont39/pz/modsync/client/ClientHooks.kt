package com.dont39.pz.modsync.client

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import com.pzlauncher.zabric.hook.ModifyVariable
import com.pzlauncher.zabric.hook.Self
import zombie.core.Core
import zombie.core.network.ByteBufferReader
import zombie.gameStates.ConnectToServerState
import zombie.gameStates.IngameState
import zombie.network.ConnectionDetails

/** The client's end of the location trailer, read before the join reads the data; the server's is `DetailsWrite`. */
@Hooks(ConnectionDetails::class)
internal object DetailsParse {
  // always: also ends the previous join's session and swap once the feature is off
  @Inject(method = ["parse"], at = [At("HEAD")], always = true)
  fun parsing(details: ByteBufferReader?, ci: CallbackInfo) {
    ModSyncClient.joinStarts(details)
  }
}

/** Every failure of a join's Workshop item passes here, after the item wrote its error. */
@Hooks(targets = ["zombie.gameStates.ConnectToServerState\$WorkshopItem"])
internal object WorkshopItemFail {
  @ModifyVariable(method = ["setState"], at = At("HEAD"), argsOnly = true)
  fun next(
    next: ConnectToServerState.WorkshopItemState?,
    @Self item: ConnectToServerState.WorkshopItem,
  ): ConnectToServerState.WorkshopItemState? =
    if (next == ConnectToServerState.WorkshopItemState.Fail && ModSyncClient.takeOver(item)) {
      ConnectToServerState.WorkshopItemState.Ready
    } else {
      next
    }
}

@Hooks(ConnectToServerState::class)
internal object JoinState {
  // always: a join with taken items must stop, not go on with the player's own copy, once the feature is off
  @Inject(method = ["CheckMods"], at = [At("HEAD")], cancellable = true, always = true)
  fun checkMods(@Self join: ConnectToServerState, ci: CallbackInfo) {
    if (ModSyncClient.hold(join)) {
      ci.cancel()
    }
  }

  // always: undoes the swap once the feature is off, including after an error
  @Inject(method = ["exit"], at = [At("HEAD")], always = true)
  fun exit(ci: CallbackInfo) {
    ModSyncClient.joinEnded()
  }
}

/** Where the game leaves a server's mods; always, as they undo the swap. */
@Hooks(Core::class)
internal object LuaReset {
  @Inject(method = ["ResetLua(Ljava/lang/String;Ljava/lang/String;)V"], at = [At("HEAD")], always = true)
  fun reset(activeMods: String?, reason: String?, ci: CallbackInfo) {
    ModSyncClient.resetLua(activeMods)
  }
}

/** After the join's pass over its mod list loaded the jars. */
@Hooks(Core::class)
internal object LuaResetDone {
  @Inject(method = ["ResetLua(Ljava/lang/String;Ljava/lang/String;)V"], at = [At("RETURN")])
  fun reset(activeMods: String?, reason: String?, ci: CallbackInfo) {
    ModSyncClient.modsLoaded(activeMods)
  }
}

@Hooks(IngameState::class)
internal object GameExit {
  @Inject(method = ["exit"], at = [At("HEAD")], always = true)
  fun exit(ci: CallbackInfo) {
    ModSyncClient.leftGame()
  }
}
