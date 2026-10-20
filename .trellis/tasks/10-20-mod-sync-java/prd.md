# Mod sync Java mods: server jars, restart and rejoin

## Goal

Replace the core child's interim "a changed jar stops the join" with the parent's D7-D9 and D11: the player ends up
playing with the server's jar, restarting only when needed. Parent: [10-20-server-mod-sync](../10-20-server-mod-sync/prd.md);
findings in [java-mod-restart.md](../10-20-server-mod-sync/research/java-mod-restart.md).

Depends on `10-20-mod-sync-core` (session, plan, cache, swap) and `10-20-zabric-launch-override` (override, accessors,
widening). Starts after both are done.

## Requirements

- R1 (D7). A marked item whose plan changes a jar, and whose Java package is not loaded in this JVM: download as for
  any file; the join's pass loads the cached jar through the swap. After the pass, the client checks the jar was
  loaded (approval may be denied) and stops the join with a reason if not.
- R2 (D7, D9). Package already loaded, but not by the startup pass: download, stop the join with "restart to use this
  server's version of <mod>" and a "Quit game" button, record the server for Rejoin.
- R3 (D8). Package loaded by the startup pass (main-menu list or preload entry): as R2, plus a Zabric one-launch
  override for the next launch.
- R4 (D9). After the restart, the main menu offers "Rejoin <server>" through the vanilla join with the saved server
  entry and account; the offer is dropped once used or when the player dismisses it.
- R5 (D7). Items that carry ZombieBuddy, Zabric, ZabricKotlin or Dont39 All-in-One stop the join with a reason.
- R6 (D11). The confirm dialog marks each item whose download changes a jar.
- R7. Two servers needing different versions of the same jar each work after their own restart.

## Acceptance Criteria

- [ ] R1: a Java mod not enabled on the main menu, changed on the Workshop after the server started, works on that
      server without a restart.
- [ ] R2/R4: the same after joining another server that loaded the client's version: restart, Rejoin, play.
- [ ] R3: the same for a Java mod enabled on the main menu; the launch after the rejoin is back to the client's copy.
- [ ] R1: a denied ZombieBuddy approval stops the join with a reason.
- [ ] R5/R6/R7 as stated; build gate, `smokeTest`, in game on the Linux test server.
