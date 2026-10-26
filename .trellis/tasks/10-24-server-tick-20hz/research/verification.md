# Verification (2026-10-24)

Linux test instance `myserver2` (`-nosteam`, dev builds of Zabric, ZabricKotlin, Dont39 All-in-One), save restored
from the research snapshot before each run, 8 fake clients as in [findings.md](./findings.md). A temporary probe
(the prototype's `TickProbe`, not committed) logged one line per 10 s, plus `Rand.AdjustForFramerate(30)`.
Afterwards the instance's ini, sandbox file, mods and save were restored.

| AC | Result |
|----|--------|
| AC1 | Option 20: `on: 20 ticks per second (period 50 ms)`, then `the server ticked 20.01 times per second in its first 10 s`. Loaded (17 windows): 20.00 ticks/s, game-time rate 47.83 vs 47.90 at option 10 (0.15%); process CPU 0.28 vs 0.24 cores; tick work 4.9 vs 6.2 ms per tick; sent 58 vs 61 KiB/s. |
| AC2 | Option 10: `off: sandbox option Dont39AllInOne_ServerTickRate.TicksPerSecond is 10`; 10.00 ticks/s, `adjust30=10`. |
| AC3 | `TickScaleTest` (vanilla identity at 10 for n 0-200k, 20 = vanilla of 2n, chance per second within 1% for 10-20). On the server at 20: `Rand.AdjustForFramerate(30)` returned 20 in every window. |
| AC4 | Build with the expected period changed to 99 ms: `disabled after an error while setting the server's tick rate` ("GameServer.main ran a tick without checking a 99 ms limiter first"), then `disabled; the server is back at 10 ticks per second`. Frame counter 0 → 2692 over the next 277 s (≈ 9.7/s including the shutdown save). |
| AC5 | `:dont39-all-in-one:smokeTest` passed: `server-tick-rate: idle: this is not a dedicated server; ...`. Client mod list, ZombieBuddy config and Loading Progress reports unchanged. |
| AC6 | Partly, accepted by the user on 2026-10-26. The user joined `myserver2` at 20 Hz from the Windows client (Steam mode; the 4 test mods) and played a short session. The only problem found: the option's tooltip had a bare `%`, so the game's translation formatting threw `UnknownFormatConversionException`; fixed in 66c768b (`%%`, as vanilla). A second run mirrored `myserver` (244 mods, its ini, sandbox and JVM options; Workshop copies of Zabric, ZabricKotlin and All-in-One replaced by the dev builds) and ticked at 20.00/s, but no player joined it. Not covered: the full 10 minutes with driving, and play with the full mod list. |
| AC7 | `./gradlew build :server-tick-rate:test assembleMod` passed. |
