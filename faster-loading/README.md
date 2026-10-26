# Dont39FasterLoading

A client-only Java mod that shortens Project Zomboid's loads (launch to the main menu, server join and single-player
start) by doing the same work faster: with the game's own results, or, for texture compression, the mod's own encoder
(BC7 on the GPU, or the driver's DXT5 format on the CPU). One switch per fix. Made for game 42.21 revision
`4a0e9546ec`. Workshop text: `mod/workshop/workshop.txt`.

## Fixes

| Switch | Hook | What it does |
|---|---|---|
| `stripComments` | `ScriptParser.stripComments(String)`, `@ModifyVariable` on the argument at HEAD (`hooks/ScriptHooks.java`) | Hands the game's method `comments/Comments.java`'s result, linear where the game's method cuts each comment out of one buffer (about 2 s for the game's `tileGeometry.txt` alone, per launch and per join). Same output for every input, the game's quirks included (markers inside strings, two-level nesting, a stray `*/` that stops it, `/*/`, markers formed across a cut); the game's method returns it unchanged after one scan (about 10 ms for the hooked call on `tileGeometry.txt`) |
| `textureCompression` | `GL11.glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V`, cancellable `@Inject` at HEAD (`hooks/TextureHooks.java`) | Compresses the levels the game asks the driver to compress (the call shape in `textures/Upload.java`) with the mod's own encoder and defines them; other uploads go to the driver untouched. The driver compresses on the render thread, about 20 ms per tile page: a join with many tile mods waits about a minute on it when the game's texture compression option is on. `textures/Encoders.java` picks the encoder once per session at the first level, so every level of every texture has one format: BC7 on the GPU (`textureBc7`), else DXT5, the format NVIDIA's driver picks, by `textures/Dxt5Encoder.java` (stb_dxt colour, own alpha in `Dxt5Alpha.java`) on all cores, at or above the driver's quality on colour and alpha. Another encoder implements `textures/Encoder.java` and leaves the hook as it is |
| `textureBc7` | (the same hook; only with `textureCompression` on) | `textures/bc7/Bc7Encoder.java` encodes to BC7 (`GL_COMPRESSED_RGBA_BPTC_UNORM`, the same 1 byte a pixel as DXT5) with DirectXTex's compute shader, ported to GLSL in `src/main/resources/.../textures/bc7/BC7Encode.comp` (modes 4-7, alpha weighted 4, each pixel's nearest index in the final pass): 5.4-6 dB above DXT5 on colour and on screen (over white and over grey) on the sample tile pages, about 4.5 dB below it on alpha. The render thread uploads the game's pixels, dispatches the passes and fills the level from the GPU's output, about 1.8 ms a 1024x1024 page with all its levels against DXT5's 4.5 ms with all cores busy (mostly the driver's copy of the pixels); the GPU spends about 3 ms a page. Needs OpenGL 4.3; without it, or with the switch off, DXT5 |
| `depthMapOrder` | `TileDepthTextureManager.init()V`, `@Inject` at HEAD and RETURN (`hooks/DepthHooks.java`) | Each mod's depth maps (and the game's) decode one at a time under that mod's lock, and the game queues them mod after mod at one priority, so the 4 file workers mostly wait on one lock: 5-6 s of background loading after the main menu is up, and the last 5-6 s of a join started before that finished. At RETURN, before the game sorts the new tasks into its file queue, `hooks/DepthOrder.java` lowers their priorities (all at or below the game's 4) into the order of a greedy schedule (`depth/Schedule.java`: a free worker takes the next task of the idle mod with the most tileset rows left), so different mods' maps decode side by side and the longest start first. Same tasks, each mod's in its own order; only the order changes |
| `luaFolderWalk` | `LuaManager.searchFolders(URI, File)`, cancellable `@Inject` at HEAD (`hooks/LuaHooks.java`) | The game lists the Lua files of itself and each mod by looking up a folder's canonical path again for every entry in it, about 0.4 ms each on Windows: about 4 s per launch and per join with a large mod list. `luafiles/FolderWalk.java` lists the same files in the same order, still through `ZomboidFileSystem.getRelativeFile`, and looks the path up once for the walk's root and for each folder the lookup could change (a link or other reparse point, a name ending in a dot or space); a folder reached by a listed name of a canonical folder already has its canonical path |

## How it works

- Zabric keeps the mod preloaded (its preLaunch entrypoint) and opens its hooks at game start; `Zabric-Hooks: launch`
  keeps them open on server joins, whose mod list lacks a client-only mod.
- `hooks/Session.java` decides at the first hook call whether the fixes run: not on a dedicated server, and not on a
  game build other than revision `4a0e9546ec`, where the first hook call throws and the mod's default gate stops all
  its hooks with that reason. A fix reproduces the game's code of that build, which the next build may change.
- With a switch off, its hook leaves the call as it is and the game's own code runs.

## Switches

- One key per fix in `Dont39FasterLoading.ini` in the game cache's `Lua` folder (`settings/Settings.java` on the
  repo's shared `mod-settings/Switches.java`, keys in `settings/Fix.java`), all on by default. Read once per session
  at the first hook call; a missing file is written with the defaults, a file without a newer fix's key gets it at its
  default, an invalid one is reported in one line and the defaults apply.
- The mod options page (`src/main/lua/client/Dont39FasterLoading_Options.lua` on `mod-settings/Switches.lua`, shipped
  as `Dont39FasterLoading_Switches.lua`; text in `Translate/`) shows the same switches, set from the ini when the page
  is built, and rewrites the ini on apply; a change applies from the next launch. A new fix adds its key to `Fix.java`,
  to the page's `switches` and its text to each `UI.json`; `SettingsTest` fails until all agree.
- `textureBc7` chooses the encoder of `textureCompression` and does nothing with it off; off, the texture compression
  is Faster Loading 0.4.0's DXT5.

## Constraints

- Only the generated glue and `Mod` (the mod's id and log prefix, JDK types only) live in `com.dont39.pz.fasterloading`:
  ZombieBuddy loads every class there at premain.
- ZombieBuddy loads the types in the glue's signatures at premain, so only methods whose signatures name JDK types are
  hooked, never with `@Self`; `checkJdkGlue` fails the build otherwise.
- Hooks do not cancel the game's method: ZombieBuddy stacks the advice of several mods on one method in load order,
  and a cancel by the outer one would skip the inner one's, such as Loading Progress's known-call bracket on
  `stripComments`. Two exceptions. `glTexImage2D`: the upload must be replaced, and no argument change makes it take
  compressed blocks. It cancels only the calls it uploads itself, and no mod on the live mod list patches `GL11`.
  `searchFolders`: the repeated lookups are inside the method, so the hook walks the whole folder and cancels the
  outermost call, after the walk succeeded and appended its list; no mod on the live mod list patches it.
- A failure of the Lua folder walk (a folder that cannot be listed, an I/O error of a lookup) throws before anything
  reaches the game's list and stops all the mod's hooks; that call then runs the game's own walk.
- The depth map order reads the game's file queue and depth-map tasks through members
  `src/main/resources/META-INF/dont39/faster-loading.classtweaker` lists, which Zabric makes public at game start (the
  jar ships it as `META-INF/zabric/Dont39FasterLoading.classtweaker`). The main entrypoint logs whether the list is in
  effect (`[Dont39FasterLoading] access widener: ...`), or the first depth maps do when the mod is only preloaded and
  the mod list lacks it, as Zabric then never calls the main entrypoint; when it is not, the depth map order stays idle
  and says so. It
  reads the tasks not yet sorted in under the queue's own lock, the way the game queues them, and releases it before
  anything else. A batch that is not as the game queues it (count, priority, a tileset without rows, another file system
  or worker pool) throws before any priority changes and stops all the mod's hooks.
- A failure of the texture upload (DXT5 chosen on a GL without `GL_EXT_texture_compression_s3tc`, a BC7 program that
  does not compile or link, a GL error after a level, a level with other than tightly packed rows, which goes to the
  driver, when the driver compresses `GL_COMPRESSED_RGBA` to another format than the encoder's) throws and stops all
  the mod's hooks; that call then runs the game's own `glTexImage2D`. A GL without OpenGL 4.3 is not a failure: the
  encoder is DXT5, and the choice's log line (`[Dont39FasterLoading] texture compression: ...`) names the reason, the
  GL's renderer and version, and the driver's format for `GL_COMPRESSED_RGBA`, probed once on a scratch texture.
- The BC7 encoder works on the game's render thread between the game's own draws: it leaves the current program and
  the shader storage buffer bindings it uses (generic, and base or range at 0-2) as it found them, and the pixel unpack
  buffer at 0, which the hook checked before (`textures/bc7/GlState.java`), touches no texture unit, framebuffer or
  game uniform, and sets no uniform of its own (an update waits for the dispatches in flight; the sizes come in the
  input buffer). NVIDIA's driver reallocates a texture's storage when a level is added and waits for the GPU writes
  pending into it, so the encoder allocates a mipmapped texture's whole chain at its first level, down to 1x1, and fills
  each level as the game sends it; a texture the game does not mipmap (a min filter without mipmaps) gets its level
  alone. Each `glGet*` waits for the driver's thread, so a level of the chain it allocated skips the queries for its
  storage.
- Video memory: a BC7 texture takes what the same DXT5 texture takes (1 byte a pixel, the same levels). The encoder's
  own buffers are fixed at about 35 MB for the session, sized for a 1024x1024 level; a larger level goes through them in
  strips of whole block rows, filled into the level at their rows. Buffers that grew with the largest level held about
  0.9 GB more than DXT5 in a join with the live mod list; measured since: within 39 MiB of DXT5's peak (7.4 GB with the
  live mod list on a 3840x2054 window).
- `BC7Encode.comp` is a port of `DirectXTex/Shaders/BC7Encode.hlsl` (Copyright (c) Microsoft Corporation, MIT) at
  commit `ec66b78b`; the file lists its changes, and the license ships as `META-INF/licenses/DirectXTex.txt`.

## Checks

`./gradlew :faster-loading:check` runs the unit tests and `checkJdkGlue`. The comment stripping tests run the game's own
method from `projectzomboid.jar` as the reference, and check that it returns the result unchanged: the quirk cases,
every string of `/`, `*` and `x` up to length 12, random text and nesting, and every script and tile-format file under
the game's `media` and the installed Workshop items (skipped when a folder is not there); one test logs the time of the
game's method and of the hooked call on the game's `tileGeometry.txt`. The texture tests decode the encoder's DXT5 with
a decoder in the test sources: PSNR floors on a generated sprite, levels of any size padded as whole blocks, the
parallel output equal to the single-threaded one, and alpha blocks exact where the format allows, close to a brute-force
search and never worse than stb_dxt's. `Upload.requested` is tested on the call shapes it takes and leaves to the
driver; `Encoders.choose` on each combination of the switch and OpenGL 4.3, with its reason; the BC7 dispatch grid (at
most 65,535 work groups a dimension, fewer groups past the level's blocks than rows) and its strips (whole block rows
that fit the encoder's buffers) on every level size from 1x1 to 4096x4096. The depth map schedule is tested on its
own: mods alternate, the most work starts first, each mod keeps its order, every priority is at or below the game's, a
batch of one mod is left as it is, a batch shaped like a measured join finishes near the bound no order can beat, and
the order equals the task's simulator (`order_greedy`) on a generated batch for 4 and 2 workers; `Batch` is tested on
the mismatches it reports. The Lua folder walk is compared with the game's own `searchFolders` from
`projectzomboid.jar`, list and order: on the game's and every installed Workshop mod's Lua folders, with one canonical
lookup per root there, and on generated trees (upper-case extensions, empty folders, a root that is a file or missing, a
junction, a symbolic link where the user may create one, names ending in a dot or space through `\\?\` paths); a folder
that cannot be listed throws.

`:faster-loading:qualityCheck` (not part of `check`: it needs a GPU with OpenGL 4.3) compresses up to 6 pages each of
four installed tile packs with the driver's `GL_COMPRESSED_RGBA`, with DXT5 and with BC7, reads level 0 back from the
GPU and fails when DXT5 is below the driver on colour or alpha, or when BC7 is not above DXT5 on colour, over white and
over grey; it prints alpha and the largest errors too. It defines full BC7 mip chains of odd sizes down to 1x1 and
2048x2048 and 1501x1099 levels without mipmaps (several strips, more work groups than one row of the dispatch grid,
partial blocks at the right and bottom edge) and fails when a level is not BPTC of its size or decodes below a floor
(each strip of the large levels too), when a level without mipmaps has a smaller level allocated, and when a BC7
level changes the GL state around it (a program, a range-bound, a base-bound and an unbound storage buffer at the
encoder's binding points, the generic binding, a texture on another unit). Last it times every page with all its
levels into a new texture, at the pages' own sizes and padded to the power-of-two size the game uploads (its
`widthHw`): GL-thread time, time until the GPU is done and GPU time, medians of 7 runs; it fails when BC7 takes more
than 40 % of DXT5's GL-thread time a page at the pages' own sizes, where the limit was set (padded, BC7 also copies
the padding DXT5 encodes almost for free: 37-41 %, printed only). It also fails when the probe of the driver's format without pixels differs from what the
driver gave a page. With `-PqualityDump=<dir>` it writes a SHA-256 of every BC7 level it defined to
`<dir>/bc7-blocks.txt`, to compare an encoder change bit for bit.
`:faster-loading:smokeTest` launches the game until init and checks the log for the mod's lines and that its access
widener list is in effect.
