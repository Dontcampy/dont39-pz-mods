-- Faster Loading: a switch per fix on the mod options page. The Java side reads the same file once per launch, at
-- the first load step it speeds up, so a change applies from the next launch of the game.

local Switches = require "Dont39FasterLoading_Switches"

Switches.page({
    id = "Dont39FasterLoading",
    name = "Faster Loading",
    -- in the cache folder's Lua/, where settings/Settings.java reads it
    file = "Dont39FasterLoading.ini",
    -- settings/Fix.java, in its order (SettingsTest checks)
    switches = {
        {
            key = "stripComments",
            text = "StripComments",
            description = "remove the comments of script and tile files in one pass, with the game's result",
        },
        {
            key = "textureCompression",
            text = "TextureCompression",
            description = "compress the textures the game asks the graphics driver to compress with the mod's own"
                .. " encoder, BC7 on the GPU (textureBc7) or DXT5 on all CPU cores",
        },
        {
            key = "textureBc7",
            text = "TextureBc7",
            description = "with textureCompression on and OpenGL 4.3, compress to BC7 on the GPU, sharper at the same"
                .. " video memory; off, or without OpenGL 4.3: DXT5 on all CPU cores",
        },
        {
            key = "depthMapOrder",
            text = "DepthMapOrder",
            description = "load the depth maps of the game and of different mods side by side instead of one mod"
                .. " after another",
        },
        {
            key = "luaFolderWalk",
            text = "LuaFolderWalk",
            description = "list the Lua files of the game and the mods without looking up each folder's full path"
                .. " once per entry in it, with the game's list",
        },
    },
})
