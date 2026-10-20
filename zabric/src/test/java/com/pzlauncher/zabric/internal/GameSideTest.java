package com.pzlauncher.zabric.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GameSideTest {
    @Test
    void theMainClassTellsTheSide() {
        assertEquals(GameSide.CLIENT, GameSide.ofCommand("zombie.gameStates.MainScreenState -cachedir=C:/Z"));
        assertEquals(GameSide.CLIENT, GameSide.ofCommand("zombie/gameStates/MainScreenState"));
        assertEquals(GameSide.SERVER, GameSide.ofCommand(" zombie.network.GameServer -servername s"));
        assertNull(GameSide.ofCommand("zombie.network.GameServerX"));
        assertNull(GameSide.ofCommand("-cachedir=x zombie.network.GameServer"));
        assertNull(GameSide.ofCommand(""));
    }
}
