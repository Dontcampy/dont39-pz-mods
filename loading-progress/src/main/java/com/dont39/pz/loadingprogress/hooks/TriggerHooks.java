package com.dont39.pz.loadingprogress.hooks;

import com.dont39.pz.loadingprogress.progress.Known;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.Thrown;
import se.krka.kahlua.vm.KahluaThread;
import zombie.DebugFileWatcher;
import zombie.Lua.LuaManager;
import zombie.gameStates.ChooseGameInfo;
import zombie.network.CoopMaster;
import zombie.network.GameClient;
import zombie.network.NetChecksum;
import zombie.scripting.ScriptBucket;
import zombie.scripting.ScriptParser;
import zombie.ui.TextManager;

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

    // per mod in every manager's loop; on the launch only after the first pass, which weaves it
    @Hooks(ChooseGameInfo.class)
    public static final class ModDetails {
        private ModDetails() {
        }

        @Inject(method = "getAvailableModDetails", at = @At("HEAD"))
        public static void details(CallbackInfoReturnable<ChooseGameInfo.Mod> cir) {
            Loading.tick();
        }

        // rereads a mod.info file on each call: mods that look at every mod folder in OnGameBoot call it a thousand
        // times
        @Inject(method = "readModInfo(Ljava/lang/String;)Lzombie/gameStates/ChooseGameInfo$Mod;", at = @At("HEAD"))
        public static void modInfo(CallbackInfoReturnable<ChooseGameInfo.Mod> cir) {
            Loading.tick();
        }
    }

    // polled every 10 ms while font pages load, e.g. the Chinese fonts after the translations
    @Hooks(TextManager.class)
    public static final class Fonts {
        private Fonts() {
        }

        @Inject(method = "isAnyFontLoading()Z", at = @At("HEAD"))
        public static void loading(CallbackInfoReturnable<Boolean> cir) {
            Loading.tick();
        }
    }

    // per block of a script-format file: every script object after the script files were read, and tile files
    @Hooks(ScriptBucket.class)
    public static final class ScriptObjects {
        private ScriptObjects() {
        }

        @Inject(method = "getCurrentScriptObject()Ljava/lang/String;", at = @At("HEAD"))
        public static void block(CallbackInfoReturnable<String> cir) {
            Loading.tick();
        }
    }

    // per class the game exposes to Lua when it creates its Lua state; many load for the first time there, and
    // ZombieBuddy patches each
    @Hooks(LuaManager.Exposer.class)
    public static final class Exposer {
        private Exposer() {
        }

        @Inject(method = "setExposed(Ljava/lang/Class;)V", at = @At("HEAD"))
        public static void exposed(CallbackInfo ci) {
            Loading.tick();
        }
    }

    // per directory of each mod's media folder at mod discovery: one mod can have ten thousand
    @Hooks(DebugFileWatcher.class)
    public static final class FileWatcher {
        private FileWatcher() {
        }

        @Inject(method = "registerDir(Ljava/nio/file/Path;)V", at = @At("HEAD"))
        public static void directory(CallbackInfo ci) {
            Loading.tick();
        }
    }

    // one long call per script-format file with only JDK code inside: the frame-gap line names its share
    @Hooks(ScriptParser.class)
    public static final class Comments {
        private Comments() {
        }

        @Inject(method = "stripComments(Ljava/lang/String;)Ljava/lang/String;", at = @At("HEAD"))
        public static void stripping(CallbackInfoReturnable<String> cir) {
            Loading.knownCall(Known.COMMENT_STRIPPING);
        }

        @Inject(method = "stripComments(Ljava/lang/String;)Ljava/lang/String;", at = @At("FINALLY"))
        public static void stripped(CallbackInfoReturnable<String> cir, @Thrown Throwable thrown) {
            Loading.knownCallEnded();
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
