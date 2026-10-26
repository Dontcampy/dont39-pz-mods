# dont39-pz-mods

Project Zomboid (Build 42) mods on [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy).

## Modules

| Module | What it is |
|--------|------------|
| `zabric`, `zabric-processor` | Zabric: loader layer, hook API and runtime (a mod), and its annotation processor; both on Maven Central |
| `zb-kt`, `zb-kt-utils`, `zb-kt-utils-processor` | ZabricKotlin: the Kotlin runtime mod, the shared runtime it ships, and its KSP glue generator |
| `dont39-all-in-one` | Dont39 All-in-One: the mod that bundles the feature modules |
| `ime-keyboard-fix`, `missing-anim-folders`, `anim-extends-case`, `tunnel`, `network-status`, `mod-options-sidebar` | the features, one module each |
| `server-reborn` | Server Reborn: network sync optimization and the server tick rate, a Kotlin mod for multiplayer servers |
| `zb-skip-online-checks` | ZB Skip Online Checks, a mod for ZombieBuddy itself |
| `buildSrc` | the convention plugins and build tasks every module uses |
| `samples/zabric-java-hooks` | a standalone author build against the published Zabric artifacts |

## Local setup

- JDK 25 for Gradle (`JAVA_HOME`); `buildSrc/build.gradle.kts` requires it.
- Machine paths go in `~/.gradle/gradle.properties`, not in the repo:

  ```properties
  # the Project Zomboid install, with projectzomboid.jar and ZombieBuddy.jar
  pzDir=C:/Program Files (x86)/Steam/steamapps/common/ProjectZomboid
  # the dedicated server, for :zb-kt:serverSmokeTest only
  pzServerDir=C:/Program Files (x86)/Steam/steamapps/common/Project Zomboid Dedicated Server
  ```

  `pzUserDir` (default `~/Zomboid`) is where `deployWorkshop` stages the mods and the smoke tests read the log.
- Feature target mods and ZombieBuddy subscribed in Steam.

`./gradlew build assembleMod` builds and checks everything; `deployWorkshop` stages a mod for the game and for upload.

## Writing Java mods on Zabric

Zabric hooks are published as `com.pzlauncher:zabric` (compileOnly) and `com.pzlauncher:zabric-processor`
(annotationProcessor). [`samples/zabric-java-hooks`](samples/zabric-java-hooks) is a complete author build;
[`zabric/README.md`](zabric/README.md) is the author contract, and [`zb-kt/README.md`](zb-kt/README.md) covers Kotlin
mods on ZabricKotlin.

## Releasing Zabric

Bump `zabric` in `gradle/libs.versions.toml`, merge, then push a tag `zabric-v<version>` on that commit. The
[release workflow](.github/workflows/release-zabric.yml) checks the tag against the catalog, runs the checks and waits
for approval in the `maven-central` Environment before publishing; a Maven Central release cannot be replaced.

One-time setup: a Central Portal account with the `com.pzlauncher` namespace verified, a GPG key published to a
keyserver, and the Environment `maven-central` with required reviewers and the secrets `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD` (a Central user token), `SIGNING_KEY` (ASCII-armored private key) and `SIGNING_KEY_PASSWORD`.

## License

MIT, see [LICENSE](LICENSE).
