# Implement: mod sync core

## Checklist

1. [x] Spike: confirm with javap the leave points and cache resets (design "Hook Details"), and a weave test for the
       two risky hook shapes (`@ModifyVariable` on the package-private nested `WorkshopItem.setState`, cancellable HEAD
       on private `CheckMods`). Stop and revise the design if either fails.
2. [x] Module skeleton per [directory-structure.md](../../spec/mod/directory-structure.md#adding-a-feature): settings,
       All-in-One feature list (after `tunnel`), facade, sandbox option, `.classtweaker`, workshop.txt section.
3. [x] Game-free parts with unit tests: `Trailer`, `Manifest`, `Plan`, `SafePath`, `ServerStore`, `HttpService` +
       `Fetcher` end to end on 127.0.0.1.
4. [x] Server side: manifest at `startServer`, HTTP service, trailer at `ConnectionDetails.write`.
5. [x] Client side: trailer read, mark, `CheckMods` state machine, swap, leave hooks.
6. [x] Lua: confirm dialog and progress; EN and CN texts.
7. [x] Build gate and `smokeTest`; in game on the Linux test server: every acceptance criterion in `prd.md`, including
       a hold of several minutes (does the server drop a held client? see the parent's Risks).

## Validation

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
```

## Rollback Points

- After step 1: the design changes if a hook shape cannot weave or the leave points differ.
- After step 7's hold test: if the server drops held clients, switch to download-then-rejoin before release.

## Refactor After Review (2026-10-20)

No behavior change: same log lines, texts, wire format and Lua globals, except where a shared helper words a case
better (an `EOFException` is "closed by the other end" in mod-sync too).

8. [x] Shared module `allinone-common` (package `com.dont39.pz.common`), bundled only into Dont39 All-in-One:
       - Build: `pz-bundled` keeps the Lua/media and `.classtweaker` variants; the feature descriptor moves to
         `pz-feature`. `allinone-common` applies `pz-kotlin`, `pz-bundled`, `pz-junit`. `pz-feature` adds it
         compile-only (and for tests). `dont39-all-in-one` resolves it through its own `bundledLibs` scope: classes
         into the jar, mod files into `mergeMedia` (before the features'). Not in the features list.
       - Kotlin: sandbox option reads (`TunnelServer.option`, `ModSyncServer.portOption`), `describe(IOException)`
         (`TunnelIo`, `JoinSession`), the join failure text + `OnConnectFailed` (`TunnelClient.stop`,
         `ModSyncClient.stop`), atomic temp-file write (`TunnelStore.write`, `ServerStore.write`), the
         `<host>:<game port>` key (`TunnelStore`, `ServerStore`), `KahluaTable.put(key, Number)` (three status tables).
       - Lua: `Dont39AllInOne_Common.lua` (returns its table, like `Dont39AllInOne_TunnelShared.lua`): the fail-once
         guard of the four client files, and `log` from `TunnelShared`.
9. [x] mod-sync packages: `protocol/` (Protocol constants, Trailer encode/decode, Manifest model + parse/validate,
       SafePath, SyncException), `server/` (ModSyncServer, HttpService, ManifestBuilder from `Manifest.build`, hooks
       incl. `ConnectionDetails.write`), `client/` (ModSyncClient, hooks incl. `ConnectionDetails.parse`, JoinSession,
       JoinStatus, Worker, Refusal; the URL building now in `Trailer.base`/`location`), `client/download/` (Fetcher,
       Plan, LocalCopy, Cache, ServerStore, TreeIo), `client/swap/` (Swap, FolderSwap, SwapEntry). One top-level type
       per file where the file is named after it. Tests follow.
10. [x] Abstractions: no nullable `Work.plan` (reused vs built work as separate types or lists, no `!!`); one stop
        value shared by the worker exception and `Step.Stop`; one hash-while-copying helper in TreeIo for `sha256`
        and `writeVerified`; `Worker.await` out of production code if tests can do without it.
11. [x] Build gate, compare the assembled mod's Lua/media with before, smokeTest (only if Zabric is not redeployed
        over the other worktree's 0.5.0), spec updates.
        Done: build, the assembled mod compared with the pre-refactor one (texts, sandbox options, classtweaker and glue
        names identical), smokeTest run with `-x :zabric:deployWorkshop -x :zb-kt:deployWorkshop` against the
        installed Zabric/ZabricKotlin 0.5.0, no Lua errors from the changed files at the main menu. Not run in a join.
