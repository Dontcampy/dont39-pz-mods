package com.dont39.pz.zbskiponlinechecks.entrypoint;

import com.dont39.pz.zbskiponlinechecks.Installer;
import com.dont39.pz.zbskiponlinechecks.Skips;
import com.pzlauncher.zabric.entrypoint.MainEntrypoint;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.gameStates.ChooseGameInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Reports, once the game log is up, what the game-start code did. */
public final class MainEntry implements MainEntrypoint {
    private static final String MOD_ID = "ZBSkipOnlineChecks";
    // javaJarFile in mod.info
    private static final String JAR = "media/java/ZBSkipOnlineChecks.jar";
    private static final String PREFIX = "[" + MOD_ID + "] ";

    @Override
    public void onMain() {
        RuntimeException failure = Installer.failure();
        if (failure != null) {
            if (!PreLaunchEntry.called()) {
                DebugType.Mod.printException(failure, PREFIX + "ZombieBuddy's online checks run as before: "
                        + failure.getMessage(), LogSeverity.Error);
            }
            return;
        }
        Installer.Way way = Installer.way();
        if (way == null) {
            DebugType.Mod.warn(PREFIX + "not in effect this launch: it did not run at game start; it does from the"
                    + " next launch on");
            return;
        }
        DebugType.Mod.println(PREFIX + "skipping ZombieBuddy %s's online checks, installed by %s; skipped %s. For"
                        + " ZombieBuddy's allow-all policy only: Steam-banned Workshop jars are not skipped, and under"
                        + " prompt or deny-new signed mods fail verification", Installer.zbVersion(), way.description(),
                Skips.report());
        Throwable countError = Skips.error();
        if (countError != null) {
            DebugType.Mod.printException(countError, PREFIX + "cannot count the skipped requests",
                    LogSeverity.Error);
        }
        if (way == Installer.Way.AGENT_START) {
            checkAgentCopy();
        }
    }

    // the game-folder copy is updated by hand, so it can fall behind the mod
    private static void checkAgentCopy() {
        try {
            Path running = Path.of(Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            ChooseGameInfo.Mod mod = ChooseGameInfo.getAvailableModDetails(MOD_ID);
            if (mod == null || mod.getVersionDir() == null) {
                throw new IllegalStateException("the game does not know the folder of mod " + MOD_ID);
            }
            Path modJar = Path.of(mod.getVersionDir()).resolve(JAR);
            if (!Arrays.equals(sha256(running), sha256(modJar))) {
                DebugType.Mod.error(PREFIX + "the copy that starts with the game, %s, differs from the mod's jar %s;"
                        + " copy the mod's jar there again", running, modJar);
            }
        } catch (Exception | LinkageError e) {
            DebugType.Mod.printException(e, PREFIX + "cannot compare the copy that starts with the game with the"
                    + " mod's jar", LogSeverity.Error);
        }
    }

    private static byte[] sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }
}
