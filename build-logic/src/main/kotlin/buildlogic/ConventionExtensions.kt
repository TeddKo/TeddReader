package buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.findLibrary(name: String): Provider<MinimalExternalModuleDependency> =
    libs.findLibrary(name).get()

internal fun Project.findVersion(name: String): String =
    libs.findVersion(name).get().requiredVersion
