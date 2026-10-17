package com.dont39.pz.zbskiponlinechecks;

import me.zed_0xff.zombie_buddy.KnownAuthors;
import me.zed_0xff.zombie_buddy.SteamWorkshop;
import me.zed_0xff.zombie_buddy.ZBSVerifier;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The advice is woven into ZombieBuddy's real classes; a failed skip would hit the network and the assertions.
class InstallerTest {
    @BeforeAll
    static void install() throws ReflectiveOperationException {
        // ZombieBuddy's agent sets it at game start
        Field f = me.zed_0xff.zombie_buddy.Loader.class.getDeclaredField("g_instrumentation");
        f.setAccessible(true);
        f.set(null, ByteBuddyAgent.install());
        Installer.install(Installer.Way.MOD_ONLY);
        Installer.install(Installer.Way.AGENT_START);
    }

    @Test
    void installsOnce() {
        assertEquals(Installer.Way.MOD_ONLY, Installer.way());
        assertNull(Installer.failure());
    }

    @Test
    void banStatusIsUnknown() {
        Map<?, ?> details = SteamWorkshop.fetchItemDetails(Set.of(new SteamWorkshop.WorkshopItemID(3619862853L)));
        assertTrue(details.isEmpty());
        details.clear();
    }

    @Test
    void noAuthors() {
        assertTrue(KnownAuthors.loadAuthors().isEmpty());
    }

    @Test
    void profileKeysFail() throws ReflectiveOperationException {
        Method m = ZBSVerifier.class.getDeclaredMethod("fetchJavaModZBSHexesFromSteam", SteamWorkshop.SteamID64.class);
        m.setAccessible(true);
        InvocationTargetException e = assertThrows(InvocationTargetException.class,
                () -> m.invoke(null, new SteamWorkshop.SteamID64(76561197960287930L)));
        assertInstanceOf(IOException.class, e.getCause());
        assertTrue(Skips.report().contains("Steam profile keys 1"), Skips.report());
        assertNull(Skips.error());
    }
}
