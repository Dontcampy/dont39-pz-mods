# Mod sync Java mods: server jars, restart and rejoin

## Goal

Replace the core child's interim "a changed jar stops the join" with the parent's D7-D9 and D11: the player ends up
playing with the server's jar after one restart, with Lua and Java from the same copy for the whole launch. Parent:
[10-20-server-mod-sync](../10-20-server-mod-sync/prd.md); findings in
[java-mod-restart.md](../10-20-server-mod-sync/research/java-mod-restart.md) and
[java-integration.md](./research/java-integration.md).

Depends on `10-20-mod-sync-core` (session, plan, cache, swap) and `10-20-zabric-launch-override` (override, accessors,
widening), both done (archived).

## Decisions

- J1 (2026-10-21, user). Every marked item whose server copy changes a jar goes through one path: download, a Zabric
  one-launch override for the next launch, stop the join with a restart reason, Rejoin after the restart. No jar is
  loaded at the join. This replaces the parent's "restart only where needed" (D7) and drops the earlier R1 (load at
  the join when the package is not loaded). Why: Java classes cannot be unloaded, so a jar loaded at a join keeps
  running after leaving while Lua returns to the client's copy (a hidden mixed state); stopping a join after its pass
  is not clean; a ZombieBuddy approval mid-join and a fresh tree's widening list are unproven
  ([java-integration.md §3-4](./research/java-integration.md)). The override launch loads the server's copy from the
  startup pass on, where the approval dialog and widening already work (Zabric child, in-game check). Cost: a Java mod
  used only on that server also needs a restart.
- J2. On the override launch, an item whose package is already served by its tree's jar needs nothing: the join swaps
  to the same tree and proceeds.
- J3 (2026-10-21, user). A mismatched item holding a ZombieBuddy preload mod (`javaPreload` + `ZB-Preload`, or a
  Zabric preLaunch entrypoint) is refused with a reason, like R4. ZombieBuddy loads such a jar at premain from
  `config.json`, before the override applies, and Zabric's preload registration would then point `config.json` at the
  tree for later launches ([java-integration.md §5](./research/java-integration.md)).
- J4 (2026-10-21, user). Whenever the server copy to be used changes a jar, including a tree reused from an earlier
  join, the confirm dialog asks and marks the item as Java code that will run on this computer; "don't ask again for
  this server" covers only downloads without jar changes. The Rejoin of an answered join does not ask again.

## Requirements

- R1 (J1). A marked item whose plan changes a jar: download as for any file, request a one-launch override of the
  item to its tree (merged with any pending request), stop the join with "restart to use this server's version of
  <item>" and a "Quit game" button, record the server for Rejoin.
- R2 (J2). A join where every such item's Java package is served by that item's tree jar proceeds without a restart.
  A served jar that is not the tree's (the override did not apply, or approval was denied at startup) stops the join
  with a reason naming the item.
- R3 (D9). After the restart, the main menu offers "Rejoin <server>" through the vanilla join with the saved server
  entry and account; the offer is dropped once used or when the player dismisses it.
- R4 (D7). Items that carry ZombieBuddy, Zabric, ZabricKotlin or Dont39 All-in-One stop the join with a reason
  (already done by the core child, `Refusal.kt`).
- R4b (J3). Items holding a preload mod stop the join with "<mod> loads at game start and cannot be taken from a
  server".
- R5 (D11, J4). The confirm dialog asks whenever an item changes a jar and marks each such item.
- R6. Two servers needing different versions of the same jar each work after their own restart.

## Acceptance Criteria

- [ ] R1/R3: a Java mod changed on the Workshop after the server started: the join stops with the restart reason;
      after a restart, Rejoin joins and the session runs the server's jar and Lua.
- [ ] R1: the same for a Java mod enabled on the main menu; the launch after that is back to the client's copy.
- [ ] R2: a denied ZombieBuddy approval at the override launch's startup stops the join with a reason.
- [ ] R4b: an item with a preload mod stops the join with that reason; no override is requested (the client
      copy's flags refuse it before any download; the server's flags after the build).
- [ ] R5: a server marked "don't ask again" still asks for an item with a jar change.
- [ ] R4/R6 as stated; build gate, `smokeTest`, in game on the Linux test server.

## Out of Scope

- Loading a server's jar at the join without a restart (J1), and relaunching the game automatically (parent D9).
- Taking preload mods from a server (J3).
- Joins by Steam ID and joins without a saved server entry get no Rejoin offer, only the restart reason.
- The release: the parent's integration review (Zabric first, then All-in-One).
