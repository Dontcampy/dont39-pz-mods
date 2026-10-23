# Research: R3 ScriptParser.stripComments

- **Date**: 2026-10-21. Split from the launch-items research; index and per-item verdict: `launch-items.md`.
- Jar: `E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`, 42.21.0 `4a0e9546ec`. Citations look
  like `Class.method Lnnn`, with source lines taken from the LineNumberTable.

## R3: `ScriptParser.stripComments(String)String`

**Signature**: `public static String stripComments(String)`. It is JDK-only and static, so a preloaded mod can hook it.

**Algorithm** (`ScriptParser.stripComments L246-274`, from bytecode). It uses a `private static final StringBuilder
stringBuilder` that is shared across calls:

```
sb.setLength(0); sb.append(text);                         // L246-247
end = sb.lastIndexOf("*/");                               // L248
while (end != -1) {                                       // L249
  start = sb.lastIndexOf("/*", end - 1);                  // L250
  if (start == -1) break;                                 // L251-252
  innerEnd = sb.lastIndexOf("*/", end - 1);               // L255
  while (innerEnd > start) {                              // L256
    innerStart = start;                                   // L257
    start = sb.lastIndexOf("/*", start - 2);              // L258
    if (start == -1) break;                               // L259-260
    innerEnd = sb.lastIndexOf("*/", innerStart - 2);      // L262
  }
  if (start == -1) break;                                 // L264-265
  sb.replace(start, end + 2, "");                         // L268
  end = sb.lastIndexOf("*/", start);                      // L269
}
text = sb.toString(); sb.setLength(0); sb.trimToSize();   // L271-274
```

**Cost**:
- Each `replace` moves the tail (`System.arraycopy`), so the cost is about comments × size.
- The archived model (`.trellis/tasks/archive/2026-10/10-17-loading-progress/research/other-gaps.md:124-150`):
  - Vanilla `media/tileGeometry.txt` (6.38 MB, 21,523 comments) costs about **2.0 s per call** on this machine.
  - Mod `tileGeometry.txt` files add 0.6-2.2 s each.
- PZ_Optimization measured 1.56 s for the stripper on vanilla `tileGeometry.txt` (`docs/plan-instant-load.md` B1).
- The current reports' "tiles" step is 4,328 ms at launch and 6,411 ms in the join's "clothing, tiles and furniture"
  step. The stripper is most of each, per the archived model.
- `trimToSize()` drops the buffer after every call, so every file is copied into a fresh allocation. That cost is
  minor.

**Callers** (every call is `stripComments(text)` then `ScriptParser.parse(text)`):

| Caller | Line | Flow |
|---|---|---|
| `ScriptManager.LoadFile(ScriptLoadMode,String,boolean)` | L1149 | each script file. The text is built with `readLine` + `'\n'` (L1132-1134), so CRLF is already normalized here |
| `TileGeometryFile.parseFile(String)` | L608 | `TileGeometryManager.init` from `GameWindow.initShared` L238, and again from `Core.ResetLua` on joins and reloads |
| `SpriteModelsFile.parseFile` | L115 | sprite models |
| `SeatingFile.parseFile` | L149 | `SeatingManager.init` (`initShared` L241) |
| `SeamFile.parseFile` | L124 | `SeamManager.init` (`initShared` L240) |
| `CustomPerks.parse` | L106 | `initShared` L207 |
| `CustomSandboxOptions.parse` | L78 | `initShared` L210 |
| `ActiveModsFile.fromString` | L86 | mod list files |
| `FontsFile.fromString` | L39 | fonts |
| also `AttachmentEditorState`, `EditVehicleState` | — | debug editors |

**What a replacement must preserve exactly.** These were verified by calling the real jar method on crafted input
(scratchpad `probe/Probe.java`, `Probe2.java`; `ScriptParser` runs standalone with only the jar on the classpath):

- Only `/* */` blocks are touched. `//` line comments stay. Markers inside quoted strings are **stripped**:
  `s "/* in string */" t` becomes `s "" t`. Line endings and all other characters are untouched; CRLF stays CRLF.
- **Nesting** works for depth 2 only.
  - `a /* b /* c */ d */ e` becomes `a  e`.
  - `a /* 1 /* 2 */ 3 /* 4 */ 5 */ b` becomes `a  b`.
  - Depth 3 leaves a dangling opener: `x /* /* /* y */ */ */ z` becomes `x /*  z`.
- An **unmatched opener** stays: `a /* b` is unchanged, and `a /* x /* y */ b` becomes `a /* x  b`.
- A **stray `*/` stops all stripping before it**, because the outer loop breaks.
  - `a /* x */ */ b` is unchanged.
  - `a /* 1 */ b */ c /* 2 */ d` becomes `a /* 1 */ b */ c  d`.
- **Overlaps**: `/*/` closes itself, so `a /*/ b` becomes `a  b`. But `/*/ x */ y` is unchanged.
- **Seams**: after a removal, the next search starts at `start`, so the text left of the cut and the text right of it
  can join into a new marker.
  - `/* x */ a*/* c *//b` becomes `/* x */ a*/b`: the seam `*/` then blocks the first comment.
  - `k /* x */ a/* c */*z */ m` becomes `k  a/* c * m`.
  - Only the first two characters of the already-processed right part are ever read again, so the work stays
    bounded and a linear emulation is possible.
- Thread safety: the vanilla method uses a static buffer, so it is safe on one thread only. A replacement with local
  state is at least as safe.
- PZ_Optimization's approach (pitfall, not code):
  - It uses "one forward pass with a nesting depth" and falls back to stock when the markers are unbalanced.
  - It was verified identical on the 1,501 vanilla `.txt` files under `media/` only, not on mod files
    (`docs/override-edits.md:671-683`).
  - A forward depth counter does not reproduce the depth-3, stray-`*/`, overlap and seam cases above, so those need
    a fallback or an exact emulation.

**Hook point**: `@Inject(method = "stripComments(Ljava/lang/String;)Ljava/lang/String;", at = HEAD, cancellable =
true)` with `cir.setReturnValue(linear(text))`. It is JDK-only, so it works in a preloaded mod, and with
`Zabric-Hooks: launch` it also acts during MP joins.
- Loading Progress hooks the same method (HEAD/FINALLY known-call bracket). Its bracket may or may not run around a
  cancelled call, depending on advice order. Unverified.

