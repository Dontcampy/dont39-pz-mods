package com.dont39.pz.fasterloading.settings;

/** The mod's fixes, each with its switch; the options page lists the same keys. */
public enum Fix {
    STRIP_COMMENTS("stripComments", "comment stripping",
            "remove the comments of script and tile files in one pass, with the game's result"),
    TEXTURE_COMPRESSION("textureCompression", "texture compression",
            "compress on all CPU cores the textures the game asks the graphics driver to compress, to the same DXT5"
                    + " format");

    private final String key;
    private final String label;
    private final String description;

    Fix(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    /** Its key in the settings file. */
    public String key() {
        return key;
    }

    /** Its name in log lines. */
    public String label() {
        return label;
    }

    String description() {
        return description;
    }
}
