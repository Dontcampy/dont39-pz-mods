# Implementation plan

Build with `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`. In-game runs use the Linux test instance `myserver2`
([In-Game Verification](../../spec/mod/quality-guidelines.md#in-game-verification)); the prototype's start script,
fake-client scenario, run harness and summarizer are in [research/prototype/](./research/prototype/). Workshop
uploads are the user's.

1. Module `server-tick-rate`: `build.gradle.kts` (`pz-feature`, `pz-junit`), register in `settings.gradle.kts` and
   `dont39-all-in-one/build.gradle.kts` after `network-status` (design 1).
2. Option: `media/sandbox-options.txt`, EN/CN `Sandbox.json` (design 1).
3. Facade and apply path: `setLockFPS` and `UpdateLimit.Check` hooks, mismatch handling, measured-rate line
   (design 2). Start from the prototype's `ServerTickRate.kt`/`TickHooks.kt`; drop `TickProbe` and the system
   property.
4. Compensations: `Rand.AdjustForFramerate`, world sound lifetime; pure scaling functions with unit tests
   (design 3, AC3).
5. Texts: All-in-One `workshop.txt` line, README section with cost and known differences (R5).
6. Gate: `./gradlew build :server-tick-rate:test`, `:dont39-all-in-one:smokeTest` (AC5, AC7).
7. Linux test server, fake clients (8, as in research): option 20 (AC1, AC3 debug line), option 10 (AC2), forced
   mismatch with a temporary expected period (AC4). Back up and restore `myserver2.ini`, the sandbox file and the save.
8. Real client at 20 Hz for 10 minutes (AC6): needs the user, or report it as not done.
9. Spec update: `zabric-hooks.md` (a constructor overload that cannot be hooked alone; hooking the first call
   instead), `quality-guidelines.md` In-Game Verification (FakeClientManager as server load: `-nosteam`, one process
   per `-id`, scenario keys; tick-probe approach).

Rollback point: steps 1-6 are one commit; step 9 its own. Option default 10 keeps every server unchanged.
