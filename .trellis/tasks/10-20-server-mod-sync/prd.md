# Server mod sync: Workshop when versions match, server otherwise

## Goal

When a player joins a dedicated server, each Workshop item whose installed version matches the server's comes from the
Workshop as today; an item whose version differs is taken from the server's copy instead, so the join no longer fails
with "Workshop item version is different than the server's". This is the parent task: it owns the requirement set,
the task map and the cross-child acceptance criteria; the children own the deliverables.

## Background

- User request: "能不能开发一个从服务器同步mod的mod，不走创意工坊", refined to "如果创意工坊的版本与服务器版本一致，走创意工坊，
  否则走服务器".
- The server sends each Workshop item's id with its own install timestamp, read once at server start; the client fails
  the item with `VersionMismatch` when its install timestamp differs. An item updated on the Workshop after the server
  started therefore fails every client that has the new version. Steam serves only the latest version, so the client
  cannot fetch the server's older one from the Workshop ([workshop-hook-points.md §1-2](./research/workshop-hook-points.md)).
- The tunnel feature is the closest precedent: client connect hook, server listener on a sandbox-option port, a join
  stopped with a reason on the join screen ([ClientConnect.kt](../../../tunnel/src/main/kotlin/com/dont39/pz/tunnel/ClientConnect.kt),
  [TunnelListener.kt](../../../tunnel/src/main/kotlin/com/dont39/pz/tunnel/transport/tcp/TunnelListener.kt),
  [TunnelClient.kt](../../../tunnel/src/main/kotlin/com/dont39/pz/tunnel/TunnelClient.kt)).
- The game's bundled runtimes (client and dedicated server, Zulu 25.0.1) include `jdk.httpserver` and `java.net.http`
  (`java --list-modules` on both Windows installs).

## Research

- [workshop-hook-points.md](./research/workshop-hook-points.md): the join state machine, every Workshop error path,
  `-nosteam`, hook candidates.
- [mod-discovery.md](./research/mod-discovery.md): how a mod id resolves to a folder, which copy wins, when the server's
  mod list is applied, the Lua checksum.
- [location-delivery.md](./research/location-delivery.md): how the server hands the client the download location.
- [java-mod-restart.md](./research/java-mod-restart.md): how ZombieBuddy loads jars, approvals, restart and rejoin.

## Decisions

- D1. A Workshop item whose install timestamp equals the server's goes through the vanilla Workshop path, unchanged.
- D2. A Workshop item whose timestamp differs is taken from the server's copy instead of failing the join.
- D3. Players without the mod keep vanilla behavior; the server keeps its `WorkshopItems=` list as is.
- D4. The dedicated server serves the files over HTTP on its own TCP port, set by the owner. The player never enters a
  port or address: the server tells the client where to download from during the join. User: "用http协议。这个端口不能
  让客户端填了，要服务器下发给客户端。这样还能方便以后切换独立的静态文件服务器。"
- D5. For now the server sends only the HTTP port; the client builds the address from the host it joined
  (`GameClient.ip`) and that port. User: "目前只能下发端口，然后由客户端将ip与端口拼接成地址使用". The payload carries a
  version, so a later version can send a full address (a separate static file server) as a new payload version.
- D6. Only `VersionMismatch` is taken over. Workshop download and subscribe failures keep failing the join as vanilla
  does; no timeouts are added.
- D7. When a mismatched item's server copy has a jar that differs from the client's (by hash), the client takes the
  server's jar after one restart, whether or not its package is loaded (changed 2026-10-21 by
  [mod-sync-java J1](../archive/2026-10/10-20-mod-sync-java/prd.md): a jar loaded at a join would outlive the session). A jar
  identical to the client's needs nothing. Items carrying ZombieBuddy, Zabric, ZabricKotlin or Dont39 All-in-One, or
  a preload mod (J3), are never taken from a server.
- D8. When the mod's Java is loaded by the startup pass (enabled on the main menu, or a preload entry), Zabric gains a
  folder override for one launch, so the next launch loads the server's copy with no action from the player.
  Rejected: asking the player to disable the mod on the main menu; staging the copy under
  `Zomboid/Workshop/<x>/Contents/mods` (applies to every launch while it exists).
- D9. A join that needs a restart fails with the reason, a "Restart game" button and a "Quit game" button. "Restart
  game" (Windows clients) starts the game again with this launch's command line, working folder and environment; a
  `+connect` to another server is left out, one to this server kept in place of the Rejoin offer
  ([mod-sync-relaunch](../archive/2026-10/10-21-mod-sync-relaunch/prd.md) D5, changed 2026-10-21 from "the player
  starts the game again themself"). The main menu then offers "Rejoin <server>", which starts the vanilla join with
  the game's saved server entry and account; the feature stores no password.
- D10. The first time a client would download from a given server, it asks the player, listing the items and download
  sizes, with a "don't ask again for this server" choice; declining stops the join. Jars additionally go through
  ZombieBuddy's own approval.
- D11. No signature check of the server's jar beyond ZombieBuddy's own policy; the D10 dialog marks each item whose
  download changes a jar ("contains Java code that will run on your computer").
- D12. The code is a feature module shipped in Dont39 All-in-One, like `tunnel`; the Zabric part ships as a Zabric
  release first. The server's HTTP port is a sandbox option; 0 (default) means no HTTP service, and clients keep
  vanilla behavior for that server.

## Task Map

| Child | Delivers | Depends on |
|---|---|---|
| [10-20-mod-sync-core](../archive/2026-10/10-20-mod-sync-core/prd.md) | Server manifest, HTTP service and location trailer; client mark, confirmation, download and folder swap for items whose changed files are not jars (D1-D6, D10, D12) | — |
| [10-20-zabric-launch-override](../archive/2026-10/10-20-zabric-launch-override/prd.md) | Zabric: a mod folder override for one launch, accessors for loaded Java packages, widening for cache folders (D8) | — |
| [10-20-mod-sync-java](../archive/2026-10/10-20-mod-sync-java/prd.md) | Server jars at the join, restart with a launch override, Rejoin, refused items, the dialog's Java marking (D7-D9, D11) | core, Zabric |
| [10-21-mod-sync-relaunch](../archive/2026-10/10-21-mod-sync-relaunch/prd.md) | Zabric: restart the game with this launch's command line; mod-sync's "Restart game" (D9) | mod-sync-java |

The architecture and the contracts between the children are in [design.md](./design.md); the order and the
integration review in [implement.md](./implement.md).

## Acceptance Criteria (across children)

- [ ] A client with the mod joins a server whose Workshop item was updated after the server started: the join
      succeeds, and the session runs the server's version of that item (Lua checksum passes).
- [ ] The same item updated only in Lua or media downloads only the files whose hash differs from the client's copy.
- [ ] A client without the mod sees vanilla behavior on the same server, including the `VersionMismatch` error.
- [ ] A client with the mod on a server without the feature (or with port 0) sees vanilla behavior.
- [ ] Items whose timestamps match are installed by Steam as today, with no request to the server's HTTP port.
- [ ] After leaving the server, the main menu and single player use the client's own Workshop copies again.
- [ ] A changed jar leads to a restart and a working Rejoin, the session then running the server's jar and Lua,
      including a mod enabled on the main menu; the launch after is back to the client's copy.
- [ ] Every failure (HTTP error, hash mismatch, declined dialog, refused item) stops the join with a reason on the join
      screen; nothing falls back silently.

## Out of Scope

- Bootstrapping the mod itself: the player installs it (and ZombieBuddy, Zabric, ZabricKotlin) before joining.
- Server-only mods (in `Mods=` with no Workshop item) and `-nosteam` clients: a follow-up. They reuse the same
  `CheckMods` entry, manifest, HTTP service and cache, but add a folder instead of swapping one and download whole mods.
- Falling back to the server on Workshop download or subscribe failures (D6).
- Sending a full address, and serving the files from a separate static file server (D5 keeps the door open).
- Relaunching Linux and macOS clients (mod-sync-relaunch D4).
- Joins by Steam ID (co-op host): the client has no host to combine the port with, so they keep vanilla behavior.
