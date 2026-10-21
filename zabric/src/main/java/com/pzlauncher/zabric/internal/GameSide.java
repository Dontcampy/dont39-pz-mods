package com.pzlauncher.zabric.internal;

/**
 * Client or dedicated server, by the game's main class. A client and a server on one machine share the cache folder,
 * so files meant for one side are kept apart.
 */
public enum GameSide {
    CLIENT("zombie.gameStates.MainScreenState", "client"),
    SERVER("zombie.network.GameServer", "server");

    /** The main class of this side ({@code ProjectZomboid64.json} and the server's launch files). */
    public final String mainClass;
    /** For file names. */
    public final String id;

    GameSide(String mainClass, String id) {
        this.mainClass = mainClass;
        this.id = id;
    }

    /** The side whose main class is named {@code mainClass}, or null. */
    public static GameSide ofMainClass(String mainClass) {
        for (GameSide side : values()) {
            if (side.mainClass.equals(mainClass)) {
                return side;
            }
        }
        return null;
    }
}
