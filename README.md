# dont39-pz-mods

This project uses [Gradle](https://gradle.org/).
To build and run the application, use the *Gradle* tool window by clicking the Gradle icon in the right-hand toolbar,
or run it directly from the terminal:

* Run `./gradlew run` to build and run the application.
* Run `./gradlew build` to only build the application.
* Run `./gradlew check` to run all checks, including tests.
* Run `./gradlew clean` to clean all build outputs.

Note the usage of the Gradle Wrapper (`./gradlew`).
This is the suggested way to use Gradle in production projects.

[Learn more about the Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html).

[Learn more about Gradle tasks](https://docs.gradle.org/current/userguide/command_line_interface.html#common_tasks).

This project follows the suggested multi-module setup and consists of the `app` and `utils` subprojects.
The shared build logic was extracted to a convention plugin located in `buildSrc`.

This project uses a version catalog (see `gradle/libs.versions.toml`) to declare and version dependencies
and both a build cache and a configuration cache (see `gradle.properties`).

## Writing Java mods on Zabric

Zabric hooks are published as `com.pzlauncher:zabric` (compileOnly) and `com.pzlauncher:zabric-processor`
(annotationProcessor). [`samples/zabric-java-hooks`](samples/zabric-java-hooks) is a complete author build; the
Workshop text in [`zabric/mod/workshop/workshop.txt`](zabric/mod/workshop/workshop.txt) is the author contract.

## Releasing Zabric

Bump `zabric` in `gradle/libs.versions.toml`, merge, then push a tag `zabric-v<version>` on that commit. The
[release workflow](.github/workflows/release-zabric.yml) checks the tag against the catalog, runs the checks and waits
for approval in the `maven-central` Environment before publishing; a Maven Central release cannot be replaced.

One-time setup: a Central Portal account with the `com.pzlauncher` namespace verified, a GPG key published to a
keyserver, and the Environment `maven-central` with required reviewers and the secrets `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD` (a Central user token), `SIGNING_KEY` (ASCII-armored private key) and `SIGNING_KEY_PASSWORD`.

## License

MIT, see [LICENSE](LICENSE).
