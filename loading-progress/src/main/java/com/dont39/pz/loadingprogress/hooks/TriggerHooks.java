package com.dont39.pz.loadingprogress.hooks;

import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import se.krka.kahlua.vm.KahluaThread;
import zombie.gameStates.ChooseGameInfo;
import zombie.network.CoopMaster;
import zombie.network.GameClient;
import zombie.network.NetChecksum;

/**
 * Calls that recur inside the long loading stretches without a count of their own, where an own frame may be due. Some
 * run on every frame or Lua call; the tick checks a volatile flag first.
 */
public final class TriggerHooks {
    private TriggerHooks() {
    }

    // each protected Lua call from Java, e.g. every callback of OnGameBoot or OnGameStart
    @Hooks(KahluaThread.class)
    public static final class LuaCalls {
        private LuaCalls() {
        }

        @Inject(method = "pcall(I)I", at = @At("HEAD"))
        public static void call(CallbackInfoReturnable<Integer> cir) {
            Loading.tick();
        }
    }

    // per mod in every manager's loop, and in ZombieBuddy's Java mod pass
    @Hooks(ChooseGameInfo.class)
    public static final class ModDetails {
        private ModDetails() {
        }

        @Inject(method = "getAvailableModDetails", at = @At("HEAD"))
        public static void details(CallbackInfoReturnable<ChooseGameInfo.Mod> cir) {
            Loading.tick();
        }
    }

    // per mod list entry and per Lua file
    @Hooks(CoopMaster.class)
    public static final class Coop {
        private Coop() {
        }

        @Inject(method = "update()V", at = @At("HEAD"))
        public static void update(CallbackInfo ci) {
            Loading.tick();
        }
    }

    // the connect waits of the world load and of entering the world
    @Hooks(GameClient.class)
    public static final class Client {
        private Client() {
        }

        @Inject(method = "update()V", at = @At("HEAD"))
        public static void update(CallbackInfo ci) {
            Loading.tick();
        }
    }

    // per file of the animation checksum in a Lua reset
    @Hooks(NetChecksum.Checksummer.class)
    public static final class Checksums {
        private Checksums() {
        }

        @Inject(method = "addFile", at = @At("HEAD"))
        public static void file(CallbackInfo ci) {
            Loading.tick();
        }
    }
}
