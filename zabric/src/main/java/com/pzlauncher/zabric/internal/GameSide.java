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

    /**
     * The side whose main class starts {@code command} (the JVM's {@code sun.java.command}), or null. Without the game:
     * at premain the main class is not loaded yet.
     */
    public static GameSide ofCommand(String command) {
        String first = command.strip();
        int space = first.indexOf(' ');
        // a native launcher may name the class in its internal form
        String mainClass = (space < 0 ? first : first.substring(0, space)).replace('/', '.');
        for (GameSide side : values()) {
            if (side.mainClass.equals(mainClass)) {
                return side;
            }
        }
        return null;
    }

    /** This JVM's command line, for {@link #ofCommand}. */
    public static String command() {
        return System.getProperty("sun.java.command", "");
    }
}
