# Dont39FasterLoading

A client-only Java mod that shortens Project Zomboid's loads (launch to the main menu, server join and single-player
start) by doing the same work faster: with the game's own results, or, for texture compression, the same format from
the mod's own encoder. One switch per fix. Made for game 42.21 revision `4a0e9546ec`. Workshop text:
`mod/workshop/workshop.txt`.

## Fixes

| Switch | Hook | What it does |
|---|---|---|
| `stripComments` | `ScriptParser.stripComments(String)`, `@ModifyVariable` on the argument at HEAD (`hooks/ScriptHooks.java`) | Hands the game's method `comments/Comments.java`'s result, linear where the game's method cuts each comment out of one buffer (about 2 s for the game's `tileGeometry.txt` alone, per launch and per join). Same output for every input, the game's quirks included (markers inside strings, two-level nesting, a stray `*/` that stops it, `/*/`, markers formed across a cut); the game's method returns it unchanged after one scan (about 10 ms for the hooked call on `tileGeometry.txt`) |
| `textureCompression` | `GL11.glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V`, cancellable `@Inject` at HEAD (`hooks/TextureHooks.java`) | Compresses the levels the game asks the driver to compress (the call shape in `textures/Upload.java`) to DXT5, the format NVIDIA's driver picks, and uploads the blocks; other uploads go to the driver untouched. The driver compresses on the render thread, about 20 ms per tile page: a join with many tile mods waits about a minute on it when the game's texture compression option is on. `textures/Dxt5Encoder.java` (stb_dxt colour, own alpha in `Dxt5Alpha.java`) splits a level over all cores, at or above the driver's quality on colour and alpha. A later encoder (BC7, on the GPU, or a cache in front of one) implements `textures/Encoder.java` and leaves the hook as it is |
| `depthMapOrder` | `TileDepthTextureManager.init()V`, `@Inject` at HEAD and RETURN (`hooks/DepthHooks.java`) | Each mod's depth maps (and the game's) decode one at a time under that mod's lock, and the game queues them mod after mod at one priority, so the 4 file workers mostly wait on one lock: 5-6 s of background loading after the main menu is up, and the last 5-6 s of a join started before that finished. At RETURN, before the game sorts the new tasks into its file queue, `depth/DepthOrder.java` lowers their priorities (all at or below the game's 4) into the order of a greedy schedule (`depth/Schedule.java`: a free worker takes the next task of the idle mod with the most tileset rows left), so different mods' maps decode side by side and the longest start first. Same tasks, each mod's in its own order; only the order changes |
| `luaFolderWalk` | `LuaManager.searchFolders(URI, File)`, cancellable `@Inject` at HEAD (`hooks/LuaHooks.java`) | The game lists the Lua files of itself and each mod by looking up a folder's canonical path again for every entry in it, about 0.4 ms each on Windows: about 4 s per launch and per join with a large mod list. `luafiles/FolderWalk.java` lists the same files in the same order, still through `ZomboidFileSystem.getRelativeFile`, and looks the path up once for the walk's root and for each folder the lookup could change (a link or other reparse point, a name ending in a dot or space); a folder reached by a listed name of a canonical folder already has its canonical path |

## How it works

- Zabric keeps the mod preloaded (its preLaunch entrypoint) and opens its hooks at game start; `Zabric-Hooks: launch`
  keeps them open on server joins, whose mod list lacks a client-only mod.
- `hooks/Session.java` decides at the first hook call whether the fixes run: not on a dedicated server, and not on a
  game build other than revision `4a0e9546ec`, where the first hook call throws and the mod's default gate stops all
  its hooks with that reason. A fix reproduces the game's code of that build, which the next build may change.
- With a switch off, its hook leaves the call as it is and the game's own code runs.

## Switches

- One key per fix in `Dont39FasterLoading.ini` in the game cache's `Lua` folder (`settings/Settings.java`, keys in
  `settings/Fix.java`), all on by default. Read once per session at the first hook call; a missing file is written
  with the defaults, a file without a newer fix's key gets it at its default, an invalid one is reported in one line
  and the defaults apply.
- The mod options page (`src/main/lua/client/Dont39FasterLoading_Options.lua`, text in `Translate/`) shows the same
  switches, set from the ini when the page is built, and rewrites the ini on apply; a change applies from the next
  launch. A new fix adds its key to `Fix.java` and to the page's `FIXES`.

## Constraints

- Only the generated glue lives in `com.dont39.pz.fasterloading`: ZombieBuddy loads every class there at premain.
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
  effect (`[Dont39FasterLoading] access widener: ...`); when it is not, the depth map order stays idle and says so. It
  reads the tasks not yet sorted in under the queue's own lock, the way the game queues them, and releases it before
  anything else. A batch that is not as the game queues it (count, priority, a tileset without rows, another file system
  or worker pool) throws before any priority changes and stops all the mod's hooks.
- A failure of the texture upload (a GL without `GL_EXT_texture_compression_s3tc`, a GL error after the upload) throws
  and stops all the mod's hooks; that call then runs the game's own `glTexImage2D`.

## Checks

`./gradlew :faster-loading:check` runs the unit tests and `checkJdkGlue`. The comment stripping tests run the game's
own method from `projectzomboid.jar` as the reference, and check that it returns the result unchanged: the quirk
cases, every string of `/`, `*` and `x` up to length 12, random text and nesting, and every script and tile-format
file under the game's `media` and the installed Workshop items (skipped when a folder is not there); one test logs the
time of the game's method and of the hooked call on the game's `tileGeometry.txt`. The texture tests decode the
encoder's DXT5 with a decoder in the test sources: PSNR floors on a generated sprite, levels of any size padded as
whole blocks, the parallel output equal to the single-threaded one, and alpha blocks exact where the format allows,
close to a brute-force search and never worse than stb_dxt's. `Upload.requested` is tested on the call shapes it
takes and leaves to the driver. The depth map schedule is tested on its own: mods alternate, the most work starts
first, each mod keeps its order, every priority is at or below the game's, a batch of one mod is left as it is, a batch
shaped like a measured join finishes near the bound no order can beat, and the order equals the task's simulator
(`order_greedy`) on a generated batch for 4 and 2 workers; `Batch` is tested on the mismatches it reports. The Lua
folder walk is compared with the game's own `searchFolders` from `projectzomboid.jar`, list and order: on the game's
and every installed Workshop mod's Lua folders, with one canonical lookup per root there, and on generated trees
(upper-case extensions, empty folders, a root that is a file or missing, a junction, a symbolic link where the user
may create one, names ending in a dot or space through `\\?\` paths); a folder that cannot be listed throws.

`:faster-loading:qualityCheck` (not part of `check`: it needs a GPU) compresses up to 6 pages each of four installed
tile packs with the driver's `GL_COMPRESSED_RGBA` and with the mod's encoder, reads level 0 back from the GPU and fails
when the mod's PSNR is below the driver's on colour or alpha; it prints both and the time per page.
`:faster-loading:smokeTest` launches the game until init and checks the log for the mod's lines and that its access
widener list is in effect.
