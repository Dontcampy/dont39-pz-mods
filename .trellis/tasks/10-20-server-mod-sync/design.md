# Design: server mod sync

Architecture and the contracts the children share. Each child's own design adds its details; anything here that a
child changes is changed here first.

## Components

```
dedicated server (feature module, All-in-One)          client (same module)
─────────────────────────────────────────────          ─────────────────────────────────────────────
GameServer.startServer RETURN                          ConnectionDetails.parse HEAD
  build manifest of WorkshopItems' install folders       read the trailer → JoinSession(location) or none
  open HTTP on the sandbox-option port                 WorkshopItem.setState HEAD (ModifyVariable)
ConnectionDetails.write RETURN                           Fail + "VersionMismatch" + session → mark, Ready
  append the location trailer                          ConnectToServerState.CheckMods HEAD (cancellable)
HTTP: manifest, files by hash                            manifest → plan → confirm → download → verify
                                                         → swap on → vanilla CheckMods
                                                       ZomboidFileSystem.getInstalledItemModsFolders RETURN
                                                         swap a marked item's folder for its cache tree
                                                       leaving / failed join → swap off, caches reset
Zabric (child zabric-launch-override)                  mod-sync-java (child)
  folder override for one launch, loaded-package         jar diff → load at join / restart + override + Rejoin
  accessors, widening for cache folders
```

Hook anchors and why each point: [workshop-hook-points.md §4](./research/workshop-hook-points.md) (mark,
`CheckMods`), [mod-discovery.md §5](./research/mod-discovery.md) (swap), [location-delivery.md](./research/location-delivery.md)
(trailer), [java-mod-restart.md](./research/java-mod-restart.md) (jars).

## Contracts

### Location trailer (connection-details)

- Appended by the server after everything `ConnectionDetails.write` wrote: `[payload][int length][long magic]`,
  read by the client backwards from `limit()` at `ConnectionDetails.parse` HEAD with absolute gets (position untouched).
  Read it there, not from `ConnectToServerState`'s copy, whose limit can be its 50 MB capacity
  ([location-delivery.md](./research/location-delivery.md)).
- No magic, or a length that does not fit: no session, vanilla behavior.
- Payload v1: `[byte version = 1][unsigned short port]`. The client builds the base
  `http://<GameClient.ip>:<port>/dont39-modsync/v1/` from the host it joined (the typed host, also through the tunnel;
  an IPv6 literal goes in brackets). A join whose `GameClient.port` is 0 (by Steam ID) has no host: no session.
- The magic and the version byte live in one shared file of the feature. A later payload version can carry a full
  address (D5); a client that does not know the version has no session and logs why once.

### HTTP

- `GET <base>manifest.json`: `{"format":1,"items":[{"id":"<workshop id>","timestamp":<server install timestamp>,
  "files":[{"path":"mods/<mod>/...","size":<n>,"sha256":"<hex>"}]}]}`. Only files under the item's `mods/` folder.
  `timestamp` is the same value the server sends in connection-details, so the client matches item and version.
- `GET <base>files/<sha256>`: the file's bytes. Content addressing keeps the layout servable by a static server later.
- Server: `jdk.httpserver` on a small fixed pool, GET only, nothing outside the manifest is served. The manifest is
  built synchronously at `startServer` RETURN, before the HTTP port opens and before the main loop writes any
  connection-details, so a client never sees a trailer without a ready manifest.
- Client: `java.net.http` on a worker thread; the game thread only polls its state from the `CheckMods` hook.

### Client cache

```
<ZomboidFileSystem cache dir>/Dont39AllInOne/ModSync/
  servers.json                              "don't ask again" servers, by host:port
  items/<host>_<port>/<item id>/<timestamp>/<item id>/mods/...   one tree per server item version
```

- A tree is built from the client's own copy of the item (files whose hash matches) plus downloads, then every file is
  verified against the manifest before it is used. A verified tree is reused on later joins.
- Paths from the manifest are normalized and rejected when absolute, containing `..`, or leaving the tree.
- Under `Zomboid/`, so the game's file-access checks allow it ([mod-discovery.md §5](./research/mod-discovery.md)).

### Folder swap

- On only between the end of the `CheckMods` hold and leaving the server (or the join failing). While on, the
  `getInstalledItemModsFolders` result has each marked item's `<install>/mods` replaced by its tree's `mods`; the
  client's own copy is not in the list, so no duplicate id exists.
- Turning it on or off resets the folder list and `ChooseGameInfo`'s mod cache, and has the game's id → folder map
  ignored until its next `Reset()` (child design, "Hook Details"); it must be on before vanilla `CheckMods`, which checks
  presence and `require=` against the current resolution ([mod-discovery.md §3, §5](./research/mod-discovery.md)).
- ZombieBuddy resolves jars through the same resolution, so the swap also decides which jar a join's pass loads
  ([java-mod-restart.md §1](./research/java-mod-restart.md)).

## Failure Model

Follows [error-handling.md](../../spec/mod/error-handling.md): a mismatch in game code disables the feature; a network
or file problem is a runtime event that stops this join with a reason (`OnConnectFailed`, `forceDisconnect`, the join
ended, as `TunnelClient` does) and never falls back to vanilla behavior the player did not choose. A server without
the trailer is not an error.

## Compatibility

- Vanilla clients: the trailer sits after the last part they read ([location-delivery.md](./research/location-delivery.md)).
- Vanilla servers: no trailer, no session.
- Another mod appending to connection-details would collide with the trailer; the magic check makes that a "no
  session" case, not a misread.
- Zabric API changes only grow ([zabric-hooks.md](../../spec/mod/zabric-hooks.md#publishing-and-compatibility)).

## Risks (verify in game first)

- Hooks on package-private and private members and widening a nested class now have weave and widener tests
  ([spike.md](../10-20-mod-sync-core/research/spike.md)); the real game classes are proven only in game.
- A client held before login for 5 minutes was not dropped
  ([in-game-test.md](../10-20-mod-sync-core/research/in-game-test.md), "long hold"); longer holds are not measured.
- Texture packs are never unloaded: both copies' packs can stay in memory for the session.

## Rollback

Each child lands behind the feature's gates; port 0 on the server turns the whole path off for that server, and a
client with the feature disabled behaves as vanilla.
