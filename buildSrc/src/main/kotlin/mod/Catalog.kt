package buildsrc.convention.mod

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

private val Project.catalog: VersionCatalog
  get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** A library of gradle/libs.versions.toml, for the convention plugins, which have no typed `libs`. */
internal fun Project.catalogLibrary(alias: String): Provider<MinimalExternalModuleDependency> =
  catalog.findLibrary(alias).orElseThrow { GradleException("gradle/libs.versions.toml has no library $alias") }

private fun Project.catalogVersion(alias: String): String =
  catalog.findVersion(alias).orElseThrow { GradleException("gradle/libs.versions.toml has no version $alias") }
    .requiredVersion

/** The Kotlin runtime ZabricKotlin installs, as its ready line names it. */
fun Project.kotlinVersions(): String =
  "Kotlin ${catalogVersion("kotlin")}, kotlinx-coroutines ${catalogVersion("kotlinxCoroutines")}, " +
    "kotlinx-serialization ${catalogVersion("kotlinxSerializationJSON")}"
