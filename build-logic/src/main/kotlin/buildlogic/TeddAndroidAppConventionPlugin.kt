package buildlogic

import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class TeddAndroidAppConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        configureAndroid()
        configureDependencies()
        // Applied after the Android extension exists: the plugin inspects the module's android
        // configuration as it is applied and rejects the module outright if it cannot find it.
        pluginManager.apply("androidx.baselineprofile")
        // The plugin creates this configuration as it is applied, so the profile module can only be
        // attached afterwards.
        dependencies.add("baselineProfile", project(":baselineprofile"))
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

            // Signing material stays out of version control: .signing/ and keystore.properties are
            // both gitignored, and the path is resolved from the repository root so it reads the same
            // on any machine. A checkout without them simply builds an unsigned release rather than
            // failing on a file it was never given.
            val signing = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
                Properties().apply { file.inputStream().use(::load) }
            }
            if (signing != null) {
                signingConfigs.create("release") {
                    storeFile = rootProject.file(signing.getProperty("storeFile"))
                    storePassword = signing.getProperty("storePassword")
                    keyAlias = signing.getProperty("keyAlias")
                    keyPassword = signing.getProperty("keyPassword")
                }
            }

            buildTypes {
                // The two builds install side by side. A release signed with the release key cannot
                // replace a debug build on the same device, so measuring one against the other used to
                // mean uninstalling — and taking the reader's library and reading positions with it.
                getByName("debug") {
                    applicationIdSuffix = ".dev"
                    versionNameSuffix = "-dev"
                    manifestPlaceholders["appLabel"] = "TeddReader dev"
                }
                getByName("release") {
                    manifestPlaceholders["appLabel"] = "TeddReader"
                    // Without R8 the reader's whole composable tree is loaded and JIT-compiled the
                    // first time a book is opened, and that showed up as roughly 1.9 s between the tap
                    // and the first page — measured while the data path had already finished in 230 ms.
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    signing?.let { signingConfig = signingConfigs.getByName("release") }
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
