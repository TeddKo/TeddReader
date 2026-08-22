package buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/**
 * This project's `libs.versions.toml` version catalog, resolved once per project so a convention
 * plugin can look up a library or version by its catalog alias instead of hardcoding a
 * coordinate or version string in `build-logic`.
 *
 * @receiver The project whose `libs` catalog to resolve.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Looks up a library dependency by its alias in [libs], so a convention plugin can add it as a
 * dependency without the module's own `build.gradle.kts` repeating the coordinate.
 *
 * @receiver The project to resolve the version catalog against.
 * @param name The library's alias in `libs.versions.toml`.
 * @return The resolved dependency, ready to pass to `dependencies { ... }`.
 * @throws NoSuchElementException if [name] is not a library alias in the catalog.
 */
internal fun Project.findLibrary(name: String): Provider<MinimalExternalModuleDependency> =
    libs.findLibrary(name).get()

/**
 * Looks up a version string by its alias in [libs], for a convention plugin that needs the raw
 * version rather than a full dependency coordinate — e.g. to configure a tool that takes a
 * version string directly.
 *
 * @receiver The project to resolve the version catalog against.
 * @param name The version's alias in `libs.versions.toml`.
 * @return The alias's required version string.
 * @throws NoSuchElementException if [name] is not a version alias in the catalog.
 */
internal fun Project.findVersion(name: String): String =
    libs.findVersion(name).get().requiredVersion
