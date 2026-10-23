package com.dont39.pz.fasterloading.hooks;

import com.dont39.pz.fasterloading.luafiles.FolderWalk;
import com.dont39.pz.fasterloading.settings.Fix;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;

/** Where the game lists the Lua files of its own and of each mod before it runs them. */
@Hooks(LuaManager.class)
public final class LuaHooks {
    private LuaHooks() {
    }

    // The game looks up a folder's canonical path once per entry in it, about 4 s per launch and per join for a large
    // mod list. Only the inside of the method repeats it, so the hook walks the whole folder itself and cancels the
    // outermost call; that skips other mods' advice inside ours, and nothing else on the live mod list patches it.
    @Inject(method = "searchFolders(Ljava/net/URI;Ljava/io/File;)V", at = @At("HEAD"), cancellable = true)
    public static void searchFolders(URI base, File folder, CallbackInfo ci) throws IOException {
        if (!Session.on(Fix.LUA_FOLDER_WALK)) {
            return;
        }
        ZomboidFileSystem files = ZomboidFileSystem.instance;
        // whole before any of it reaches the list: a failure leaves the call to the game's walk
        List<String> lua = FolderWalk.lua(base, folder, files::getRelativeFile);
        LuaManager.loadList.addAll(lua);
        ci.cancel();
    }
}
