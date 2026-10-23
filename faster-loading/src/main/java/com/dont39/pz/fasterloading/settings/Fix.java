package com.dont39.pz.fasterloading.settings;

/**
 * The mod's fixes, each with its switch; the options page lists the same keys. {@link #TEXTURE_BC7} is an option of
 * {@link #TEXTURE_COMPRESSION}, not a fix of its own: it chooses that fix's encoder and does nothing with it off.
 */
public enum Fix implements Switches.Key {
    STRIP_COMMENTS("stripComments", "comment stripping",
            "remove the comments of script and tile files in one pass, with the game's result"),
    TEXTURE_COMPRESSION("textureCompression", "texture compression",
            "compress the textures the game asks the graphics driver to compress with the mod's own encoder, BC7 on"
                    + " the GPU (textureBc7) or DXT5 on all CPU cores"),
    TEXTURE_BC7("textureBc7", "texture bc7",
            "with textureCompression on and OpenGL 4.3, compress to BC7 on the GPU, sharper at the same video memory;"
                    + " off, or without OpenGL 4.3: DXT5 on all CPU cores"),
    DEPTH_MAP_ORDER("depthMapOrder", "depth map order",
            "load the depth maps of the game and of different mods side by side instead of one mod after another"),
    LUA_FOLDER_WALK("luaFolderWalk", "lua folder walk",
            "list the Lua files of the game and the mods without looking up each folder's full path once per entry in"
                    + " it, with the game's list");

    private final String key;
    private final String label;
    private final String description;

    Fix(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    @Override
    public String key() {
        return key;
    }

    /** Its name in log lines. */
    public String label() {
        return label;
    }

    @Override
    public String description() {
        return description;
    }
}
