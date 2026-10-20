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
