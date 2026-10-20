package com.dont39.pz.loadingprogress.hooks;

import com.dont39.pz.loadingprogress.draw.FramePump;
import com.dont39.pz.loadingprogress.progress.Boundary;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.GameWindow;
import zombie.core.skinnedmodel.ModelManager;
import zombie.tileDepth.TileGeometryManager;

import static com.dont39.pz.loadingprogress.hooks.Loading.PROGRESS;

/** The steps only the launch to the main menu has. */
public final class LaunchHooks {
    private LaunchHooks() {
    }

    @Hooks(GameWindow.class)
    public static final class Window {
        private Window() {
        }

        // the first frames with text can come from here on
        @Inject(method = "initFonts", at = @At("RETURN"))
        public static void fontsReady(CallbackInfo ci) {
            if (!Loading.hooksClosed()) {
                PROGRESS.fontsReady(System.nanoTime());
            }
        }

        @Inject(method = "initShared", at = @At("HEAD"))
        public static void sharedInit(CallbackInfo ci) {
            Loading.reach(Boundary.SHARED_INIT);
        }

        @Inject(method = "enter", at = @At("HEAD"))
        public static void enter(CallbackInfo ci) {
            Loading.reach(Boundary.WINDOW_ENTER);
        }

        @Inject(method = "LoadTexturePack(Ljava/lang/String;ILjava/lang/String;)V", at = @At("HEAD"))
        public static void texturePack(String pack, int flags, String folder, CallbackInfo ci) {
            PROGRESS.texturePack(pack);
            Loading.tick();
        }

        @Inject(method = "doEpilepsyWarningText", at = @At("HEAD"))
        public static void warning(CallbackInfo ci) {
            FramePump.warningShown(true);
        }

        // the game thread hands over its frames from here on
        @Inject(method = "mainThreadStart", at = @At("RETURN"))
        public static void started(CallbackInfo ci) {
            FramePump.warningShown(false);
            if (!Loading.hooksClosed()) {
                PROGRESS.launchDone(System.nanoTime());
            }
        }
    }

    @Hooks(TileGeometryManager.class)
    public static final class Tiles {
        private Tiles() {
        }

        @Inject(method = "init", at = @At("HEAD"))
        public static void init(CallbackInfo ci) {
            Loading.reach(Boundary.TILES);
        }
    }

    @Hooks(ModelManager.class)
    public static final class Models {
        private Models() {
        }

        // its frames replace the epilepsy warning
        @Inject(method = "create", at = @At("HEAD"))
        public static void create(CallbackInfo ci) {
            FramePump.warningShown(false);
            Loading.reach(Boundary.MODEL_MANAGER);
        }
    }
}
