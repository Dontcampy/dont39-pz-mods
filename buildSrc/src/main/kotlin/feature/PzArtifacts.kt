package buildsrc.convention.feature

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.ConsumableConfiguration
import org.gradle.api.artifacts.DependencyScopeConfiguration
import org.gradle.api.artifacts.ResolvableConfiguration
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.named

/** Which of the build's own variants a configuration carries or requests: one of [PzArtifact]. */
private val PZ_ARTIFACT: Attribute<String> = Attribute.of("pz.artifact", String::class.java)

// keeps these variants and the java plugin's apart in both directions: an attribute a side lacks matches anything
private const val PZ_CATEGORY = "pz-build"

private fun AttributeContainer.pz(objects: ObjectFactory, kind: String) {
  attribute(Category.CATEGORY_ATTRIBUTE, objects.named(PZ_CATEGORY))
  attribute(PZ_ARTIFACT, kind)
}

/** Values of [PZ_ARTIFACT]. */
object PzArtifact {
  /** A bundled module's Lua and media files, laid out as under the mod's `42/media` (pz-bundled). */
  const val MOD_FILES = "mod-files"

  /** A bundled module's expanded .classtweaker list, none without a list (pz-bundled, pz-feature). */
  const val CLASSTWEAKER = "classtweaker"

  /** A bundled feature's descriptor and generated listings (pz-bundled-feature, [FeatureInfo]). */
  const val FEATURE = "feature"

  /** A shipped mod's assembled folder (pz-mod). */
  const val MOD_FOLDER = "mod-folder"
}

/** A variant of this project that carries [kind]; the plugin that owns it adds the artifacts. */
fun Project.pzElements(name: String, kind: String): NamedDomainObjectProvider<ConsumableConfiguration> =
  configurations.consumable(name) {
    attributes { pz(objects, kind) }
  }

/** Resolves the [kind] variants of the projects in [from], without their dependencies. */
fun Project.pzIncoming(
  name: String,
  from: NamedDomainObjectProvider<DependencyScopeConfiguration>,
  kind: String,
): NamedDomainObjectProvider<ResolvableConfiguration> = configurations.resolvable(name) {
  extendsFrom(from.get())
  isTransitive = false
  attributes { pz(objects, kind) }
}

/**
 * Resolves the class or resource folders ([elements]: [LibraryElements.CLASSES] or [LibraryElements.RESOURCES]) of the
 * projects in [from], without their dependencies: what a mod jar bundles of other modules.
 */
fun Project.jvmFolders(
  name: String,
  from: NamedDomainObjectProvider<DependencyScopeConfiguration>,
  elements: String,
): NamedDomainObjectProvider<ResolvableConfiguration> = configurations.resolvable(name) {
  extendsFrom(from.get())
  isTransitive = false
  attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(elements))
  }
}
