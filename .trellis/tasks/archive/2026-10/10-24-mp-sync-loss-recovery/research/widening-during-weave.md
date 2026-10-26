# Widening misses classes first loaded while ZombieBuddy weaves hooks

Found in the first compatibility run (`compat-on`, 2026-10-24) on the local `sgtest` server.

## Symptom

- Server: `[Dont39SyncGuard] access widener: not applied: these are not in effect: accessible field
  zombie/network/fields/ContainerID index S (... member is private to package ...)`, same for `containerIndex`, `vid`.
  Sync Guard then idles (`MainEntry`).
- Server: `[Dont39AllInOne] access widener: not applied: ... ConnectToServerState$WorkshopItem ... not accessible`,
  while the same server without Sync Guard (`compat-off`) logs `[Dont39AllInOne] access widener: ok (11 directives)`.
  So adding Sync Guard breaks All-in-One's widening on a dedicated server.
- Client: `GameClient.MainLoopNetDataQ` not in effect.
- `[Zabric] access widener: ok (3 mods, 46 directives)`: the lists were found and the transformer installed.

## Evidence (server started with `-Xlog:class+load=info`, run `diag-classload`)

- `com.pzlauncher.zabric.PreMain` loads at 1.64 s, `WidenerTransform` at 1.94 s.
- `zombie.network.fields.ContainerID` first loads at 25.627 s, between
  `Dont39SyncGuardGlue$SyncItemModDataPacket_write source: __VM_RedefineClasses__` (25.612 s) and
  `zombie.network.packets.SyncItemModDataPacket source: __VM_RedefineClasses__` (25.628 s): inside ZombieBuddy's
  weaving of Sync Guard's `write` hook into a class that has a `ContainerID` field.
- `ConnectToServerState$WorkshopItem` first loads at 25.698 s, between the glue for `ConnectToServerState_exit` and
  `ConnectToServerState source: __VM_RedefineClasses__`: inside the weaving of Sync Guard's `ConnectToServerState.exit`
  hook (woven on the server too, though only the client uses it).

A class whose first load happens inside another class's transformation is not passed through Zabric's widener
transformer. Zabric already avoids this for its own retransforms: `WeaveCheck.retransform` calls
`getDeclaredMethods/Constructors/Fields` and `getNestMembers` first ("the loader reads these by reflection inside the
transformation; loaded here, every transformer sees them"). ZombieBuddy's own weaving of a mod's patch classes does not.

## Consequences

- Any mod's hook on a class can stop any mod's widening of that class's field types or nest members, depending on
  which loads first. Sync Guard both suffers it (`ContainerID`) and causes it (All-in-One's `WorkshopItem`).
- `smokeTest` on a client alone does not show the server case.

## Options

1. Zabric: before ZombieBuddy weaves a mod's hooks into a target, load that target's reflection data (the same calls
   `WeaveCheck.retransform` makes), so field types and nest members load outside any transformation and get widened.
   Fixes every mod combination; needs a Zabric release.
2. Sync Guard only: avoid widening the field types of its hook targets (`ContainerID` via the public
   `write(ByteBuffer)`; the client queue via a `ZomboidNetData.read` hook instead of `MainLoopNetDataQ`), and avoid
   weaving client-only targets such as `ConnectToServerState` where they can disturb other mods' widening. Fragile:
   another mod's hook can do the same to Sync Guard's remaining widened classes.
