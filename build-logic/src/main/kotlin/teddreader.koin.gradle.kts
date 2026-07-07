import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("io.insert-koin.compiler.plugin")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    dependencies.add("commonMainImplementation", libs.findLibrary("koin-core").get())
    dependencies.add("commonMainImplementation", libs.findLibrary("koin-core-viewmodel").get())
    dependencies.add("commonMainImplementation", libs.findLibrary("koin-annotations").get())
}
