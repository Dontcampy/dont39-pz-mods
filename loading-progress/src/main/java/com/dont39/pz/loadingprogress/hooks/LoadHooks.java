package com.dont39.pz.loadingprogress.hooks;

import com.dont39.pz.loadingprogress.progress.Boundary;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.Thrown;
import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;
import zombie.core.Translator;
import zombie.core.skinnedmodel.population.ClothingDecals;
import zombie.scripting.ScriptManager;
import zombie.spriteModel.SpriteModelManager;

import static com.dont39.pz.loadingprogress.hooks.Loading.PROGRESS;

/** The loading code the launch and Lua resets share: boundaries and per-item counts. */
public final class LoadHooks {
    private LoadHooks() {
    }

    @Hooks(ZomboidFileSystem.class)
    public static final class Mods {
        private Mods() {
        }

        @Inject(method = "loadMods(Ljava/lang/String;)V", at = @At("HEAD"))
        public static void loading(CallbackInfo ci) {
            PROGRESS.modsLoading(System.nanoTime());
            Loading.tick();
        }

        @Inject(method = "loadMods(Ljava/lang/String;)V", at = @At("FINALLY"))
        public static void loaded(CallbackInfo ci, @Thrown Throwable thrown) {
            PROGRESS.modsLoaded();
        }

        // the mod list is complete once loadMod runs
        @Inject(method = "loadMod", at = @At("HEAD"))
        public static void mod(String id, CallbackInfo ci) {
            PROGRESS.mod(id, ZomboidFileSystem.instance.getModIDs().size());
            Loading.tick();
        }

        // mod discovery, per root folder and per mod folder; ChooseGameInfo, which drives it, is woven only after the
        // launch's first pass
        @Inject(method = "getAllModFoldersAux(Ljava/lang/String;Ljava/util/List;)V", at = @At("HEAD"))
        public static void modRoot(CallbackInfo ci) {
            Loading.tick();
        }

        @Inject(method = "setModIdToDir(Ljava/lang/String;Ljava/lang/String;)V", at = @At("HEAD"))
        public static void modFolder(CallbackInfo ci) {
            Loading.tick();
        }
    }

    @Hooks(Translator.class)
    public static final class Translations {
        private Translations() {
        }

        @Inject(method = "loadFiles", at = @At("HEAD"))
        public static void loading(CallbackInfo ci) {
            Loading.reach(Boundary.TRANSLATIONS);
        }
    }

    @Hooks(ScriptManager.class)
    public static final class Scripts {
        private Scripts() {
        }

        @Inject(method = "Load", at = @At("HEAD"))
        public static void loading(CallbackInfo ci) {
            PROGRESS.scriptsLoading(ScriptManager.getCurrentLoadFileAbsPath(), System.nanoTime());
            Loading.tick();
        }

        @Inject(method = "Load", at = @At("FINALLY"))
        public static void loaded(CallbackInfo ci, @Thrown Throwable thrown) {
            PROGRESS.scriptsLoaded();
        }
    }

    @Hooks(SpriteModelManager.class)
    public static final class SpriteModels {
        private SpriteModels() {
        }

        @Inject(method = "init", at = @At("HEAD"))
        public static void init(CallbackInfo ci) {
            Loading.reach(Boundary.SPRITE_MODELS);
        }
    }

    @Hooks(ClothingDecals.class)
    public static final class Clothing {
        private Clothing() {
        }

        @Inject(method = "init", at = @At("HEAD"))
        public static void init(CallbackInfo ci) {
            Loading.reach(Boundary.CLOTHING);
        }
    }

    @Hooks(LuaManager.class)
    public static final class Lua {
        private Lua() {
        }

        @Inject(method = "LoadDirBase()V", at = @At("HEAD"))
        public static void loading(CallbackInfo ci) {
            Loading.reach(Boundary.LUA);
        }

        @Inject(method = "LoadDirBase(Ljava/lang/String;Z)V", at = @At("HEAD"))
        public static void folderLoading(CallbackInfo ci) {
            PROGRESS.luaLoading();
            Loading.tick();
        }

        @Inject(method = "LoadDirBase(Ljava/lang/String;Z)V", at = @At("FINALLY"))
        public static void folderLoaded(CallbackInfo ci, @Thrown Throwable thrown) {
            PROGRESS.luaLoaded();
        }

        // the overload every Lua file and require goes through
        @Inject(method = "RunLua(Ljava/lang/String;Z)Ljava/lang/Object;", at = @At("HEAD"))
        public static void file(String file, boolean rewrite, CallbackInfoReturnable<Object> cir) {
            PROGRESS.lua(file, LuaManager.loadList);
            Loading.tick();
        }

        @Inject(method = "RunLua(Ljava/lang/String;Z)Ljava/lang/Object;", at = @At("FINALLY"))
        public static void fileRan(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            PROGRESS.luaRan();
        }
    }

    // every triggerEvent overload looks its event up here, by name
    @Hooks(LuaEventManager.class)
    public static final class Events {
        private Events() {
        }

        @Inject(method = "checkEvent", at = @At("HEAD"))
        public static void event(String event, CallbackInfoReturnable<Event> cir) {
            if (PROGRESS.active()) {
                PROGRESS.event(event, System.nanoTime());
            }
            Loading.tick();
        }
    }
}
