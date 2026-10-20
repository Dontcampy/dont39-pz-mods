package com.dont39.pz.loadingprogress.hooks;

import com.dont39.pz.loadingprogress.progress.Boundary;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.Lua.LuaManager;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.gameStates.GameLoadingState;
import zombie.gameStates.IngameState;
import zombie.gameStates.LoadingQueueState;
import zombie.gameStates.MainScreenState;

import static com.dont39.pz.loadingprogress.hooks.Loading.PROGRESS;

/** A game start: the join, the world load and the first frame in the world, and the ways back to the menu. */
public final class StartHooks {
    private StartHooks() {
    }

    // the Lua functions the connect screen calls
    @Hooks(LuaManager.GlobalObject.class)
    public static final class Connect {
        private Connect() {
        }

        @Inject(method = "serverConnect", at = @At("HEAD"))
        public static void connect(CallbackInfo ci) {
            PROGRESS.connect(System.nanoTime());
        }

        @Inject(method = "serverConnectCoop", at = @At("HEAD"))
        public static void connectCoop(CallbackInfo ci) {
            PROGRESS.connect(System.nanoTime());
        }

        // the connect screen's Back button
        @Inject(method = "backToSinglePlayer", at = @At("HEAD"))
        public static void back(CallbackInfo ci) {
            PROGRESS.joinFailed(System.nanoTime());
        }

        @Inject(method = "forceDisconnect", at = @At("HEAD"))
        public static void disconnect(CallbackInfo ci) {
            PROGRESS.joinFailed(System.nanoTime());
        }
    }

    // the network library's state callback, which the connect screen hears as OnConnectionStateChanged
    @Hooks(RakNetPeerInterface.class)
    public static final class Connection {
        private Connection() {
        }

        @Inject(method = "connectionStateChangedCallback", at = @At("HEAD"))
        public static void stateChanged(String state, String message, CallbackInfo ci) {
            PROGRESS.connectionState(state, System.nanoTime());
        }
    }

    @Hooks(LoadingQueueState.class)
    public static final class Queue {
        private Queue() {
        }

        @Inject(method = "enter", at = @At("HEAD"))
        public static void enter(CallbackInfo ci) {
            Loading.reach(Boundary.LOGIN_QUEUE);
        }
    }

    @Hooks(GameLoadingState.class)
    public static final class World {
        private World() {
        }

        @Inject(method = "enter", at = @At("HEAD"))
        public static void prepare(CallbackInfo ci) {
            PROGRESS.worldPrepares(System.nanoTime());
            Loading.tick();
        }

        // the loading thread runs; the state draws its loading screen
        @Inject(method = "enter", at = @At("RETURN"))
        public static void load(CallbackInfo ci) {
            if (!Loading.hooksClosed()) {
                Loading.reach(Boundary.WORLD_LOAD);
            }
        }

        @Inject(method = "SendDone", at = @At("HEAD"))
        public static void loaded(CallbackInfo ci) {
            Loading.reach(Boundary.WORLD_READY);
        }
    }

    @Hooks(IngameState.class)
    public static final class InGame {
        private InGame() {
        }

        @Inject(method = "enter", at = @At("HEAD"))
        public static void enter(CallbackInfo ci) {
            Loading.reach(Boundary.WORLD_ENTER);
        }

        @Inject(method = "render", at = @At("HEAD"))
        public static void render(CallbackInfo ci) {
            PROGRESS.inWorld(System.nanoTime());
        }

        // before its reset to the menu's mods, which a start not yet drawn would see as steps out of order
        @Inject(method = "exit", at = @At("HEAD"))
        public static void exit(CallbackInfo ci) {
            PROGRESS.worldLeft(System.nanoTime());
        }
    }

    @Hooks(MainScreenState.class)
    public static final class Menu {
        private Menu() {
        }

        @Inject(method = "enter", at = @At("HEAD"))
        public static void enter(CallbackInfo ci) {
            PROGRESS.menu(System.nanoTime());
        }
    }
}
