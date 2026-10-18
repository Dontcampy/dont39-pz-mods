# Implement: tunnel tests and refactor

## Checklist (one commit each)

1. Apply `pz-junit`; injectable `TunnelStore` folder.
2. R1 tests (a)–(d) against current code.
3. R2 small helpers (`closeQuietly`, `UDP_BUFFER`, loopback targets, peer format).
4. R2 `LocalChannels`; `Relay` and `KcpSession` on it.
5. R3 status builder, key docs, renames in Kotlin and NetworkStatus Lua.
6. R4 Lua split.
7. Spec updates (quality-guidelines tests section, network-status/tunnel pointers).

## Validation

- Each step: `./gradlew :tunnel:test build assembleMod`.
- End: both smoke tests; deploy and in-game checks per AC3 using the Linux test server recipe (test instance, proxy
  per `quality-guidelines.md` item 6 if loss is injected); report anything not done.
