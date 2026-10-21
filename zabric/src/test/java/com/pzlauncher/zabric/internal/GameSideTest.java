package com.pzlauncher.zabric.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GameSideTest {
    @Test
    void theMainClassTellsTheSide() {
        assertEquals(GameSide.CLIENT, GameSide.ofMainClass("zombie.gameStates.MainScreenState"));
        assertEquals(GameSide.SERVER, GameSide.ofMainClass("zombie.network.GameServer"));
        assertNull(GameSide.ofMainClass("zombie.network.GameServerX"));
        assertNull(GameSide.ofMainClass(""));
    }
}
