# Fix lowercased `x_extends` animation paths on Linux servers

## Goal

On a Linux dedicated server, animation XML files that extend another file (`x_extends`) fail to load for mods whose
files sit under the server folder. Add a Dont39 All-in-One feature that makes the game open the file the XML names, so
those animation nodes load on the server as they do on Windows.

## Background

Source: the server console log (`sirius@192.168.50.249:/home/sirius/pz-only/myserver_Data/server-console.txt`,
2026-10-17, Build 42.21.0, Ubuntu, 244 mods, All-in-One 0.7.0 loaded).

- 175 `AnimNode.Parse> Exception thrown` traces, 350 `FileNotFoundException`s, from 6 mods: TrueActionsDancing (76),
  GunsOfMarz (78), LeanAndLie (10), SapphCookingB42 (5), Gunworks_gang_framework (4), tsarslib (2).
- The files exist with the case the XML gives: `x_extends="TchAL_LieOnGroundBase.xml"` →
  `.../mods/LeanAndLie/common/media/AnimSets/.../TchAL_LieOnGroundBase.xml`. The game opens
  `steamapps/workshop/content/108600/3652012357/mods/leanandlie/common/media/animsets/.../tchal_lieongroundbase.xml`.
- Cause (javap of 42.21.0): `PZXmlUtil.parseXml` passes `x_extends` (and the file name of `includeAnotherFile`) to
  `ZomboidFileSystem.resolveRelativePath(parent, name)`. That joins the two and calls `getRelativeFile`, which
  lowercases the absolute path and relativizes it against the game folder's lowercase URI. `parseXml` then calls
  `resolveFileOrGUID`, which only maps paths in `activeFileMap` (vanilla and overridden media), so a mod file's
  lowercased path reaches `FileInputStream` as is.
- It shows only when the mod files are under the game folder (relativizing fails otherwise and the original path is
  kept) and the file system is case-sensitive: a steamcmd server on Linux, where the Workshop folder is
  `<server>/steamapps/workshop`. Windows, and clients with the usual Steam layout, are not affected.
- `resolveRelativePath` has no callers besides `PZXmlUtil`.

Server-side effect of the missing nodes in MP is not verified; the fix is for the load errors and for the server having
the same animation set as the clients.

## Requirements

- R1. A new feature module in Dont39 All-in-One fixes the path that `ZomboidFileSystem.resolveRelativePath` returns:
  when the game's path does not name an existing file and the joined original-case path does, the joined path is used.
- R2. In every other case the game's result is kept: the path resolves through `activeFileMap`, the game's path exists
  (Windows, vanilla files), or neither path exists (a really missing file still gets the game's error).
- R3. The feature follows the existing feature rules: `Feature` facade, status line, hooks gated, disabled with a
  reason when the game does not match.
- R4. The Workshop description of All-in-One gets a section for the fix.

## Acceptance Criteria

- [x] AC1 (R1). With the new jar on the Linux server and a restart, `server-console.txt` has no
  `FileNotFoundException` for the six mods' `x_extends` targets, and no `AnimNode.Parse> Exception thrown` caused by
  them.
- [x] AC2 (R2). The same log has no new animation errors compared with the 2026-10-17 log, and the client smoke test
  (`:dont39-all-in-one:smokeTest`, Windows) passes with the feature `ready`.
- [x] AC3 (R3). The server log shows `[Dont39AllInOne] <feature>: ready` and Zabric reports the hook transformed.
- [x] AC4. `./gradlew build` passes (lint, checks, tests).
- [x] AC5 (R4). `workshop.txt` has the new section.

## Out of Scope

- Other findings from the log: Viewpoint's collector crashing a child JVM through `JAVA_TOOL_OPTIONS`, the 20 s Steam
  Workshop query, other mods' content errors and log noise.
- Version bump and Workshop upload of All-in-One (a later release step).
- Path case problems outside `resolveRelativePath`.
