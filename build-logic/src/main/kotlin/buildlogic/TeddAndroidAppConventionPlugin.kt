package buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class TeddAndroidAppConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        configureAndroid()
        configureDependencies()
    }

    private fun Project.configureAndroid() {
        extensions.configure<ApplicationExtension> {
            namespace = "com.tedd.teddreader"
            compileSdk = findVersion("android-compileSdk").toInt()

            defaultConfig {
                applicationId = "com.tedd.teddreader"
                minSdk = findVersion("android-minSdk").toInt()
                targetSdk = findVersion("android-targetSdk").toInt()
                versionCode = 1
                versionName = "1.0"
            }

            packaging {
                resources {
                    excludes += "/META-INF/{AL2.0,LGPL2.1}"
                }
            }

            buildTypes {
                getByName("release") {
                    isMinifyEnabled = false
                }
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
        }
    }

    private fun Project.configureDependencies() {
        dependencies.add("implementation", project(":app:reader"))
        dependencies.add("implementation", findLibrary("androidx-activity-compose"))
        dependencies.add("implementation", findLibrary("compose-uiToolingPreview"))
        dependencies.add("debugImplementation", findLibrary("compose-uiTooling"))
    }
}
