package buildlogic

import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention plugin for the Android application shell ([androidApp] module).
 *
 * Applies the Android application, Compose, and Kotlin Compose plugins; configures the
 * [ApplicationExtension] for release and debug builds (including optional signing); wires the
 * baseline-profile plugin in the order its own plugin requires; and adds the module's runtime
 * dependencies. The module's own `build.gradle.kts` stays at a single `id(...)` line because
 * every shared decision lives here.
 */
class TeddAndroidAppConventionPlugin : Plugin<Project> {
    /**
     * Applies the plugin to [target].
     *
     * The baseline-profile plugin must be applied **after** [configureAndroid] because it inspects
     * the module's Android configuration the moment it is applied and rejects the module outright
     * if it cannot find the `ApplicationExtension`. The `baselineProfile` dependency configuration
     * is likewise added after the plugin, because the plugin creates that configuration as a side
     * effect of its own application; attaching the profile module before then would fail with an
     * unresolved configuration name.
     *
     * @param target The Gradle [Project] this plugin is applied to.
     */
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        configureAndroid()
        exposeReleaseTasks()
        configureDependencies()
        pluginManager.apply("androidx.baselineprofile")
        dependencies.add("baselineProfile", project(":baselineprofile"))
    }

    /**
     * Configures the [ApplicationExtension] for this module.
     *
     * Sets the namespace, compile/min/target SDK versions, packaging exclusions, optional signing,
     * build types, and Java compatibility options.
     *
     * **Signing.** Signing material is deliberately kept out of version control: `.signing/` and
     * `keystore.properties` are both gitignored, and the path is resolved from the repository root
     * so it reads the same on any machine. A checkout without those files simply produces an
     * unsigned release build rather than failing on a file it was never given.
     *
     * **Build types.** The debug build carries a `.dev` application-ID suffix and the label
     * "TeddReader dev" so that it installs side by side with the release build on the same device.
     * Without that split, measuring debug against release required uninstalling — which erased the
     * reader's library and all stored reading positions.
     *
     * **R8 and shrinking.** The release build enables minification and resource shrinking. Without
     * R8, the reader's whole composable tree is loaded and JIT-compiled the first time a book is
     * opened, and that showed up as roughly 1.9 s between the tap and the first page — measured
     * while the data path had already finished in 230 ms. R8 eliminates that cold-start penalty by
     * AOT-optimising and pruning the tree before install.
     */
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
                getByName("debug") {
                    applicationIdSuffix = ".dev"
                    versionNameSuffix = "-dev"
                    manifestPlaceholders["appLabel"] = "TeddReader dev"
                }
                getByName("release") {
                    manifestPlaceholders["appLabel"] = "TeddReader"
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

    /** Makes AGP's ungrouped release lifecycle tasks visible in Android Studio's Gradle tool window. */
    private fun Project.exposeReleaseTasks() {
        tasks.matching { task -> task.name == "assembleRelease" || task.name == "bundleRelease" }
            .configureEach { group = "build" }
    }

    /**
     * Adds the runtime dependencies for the Android application shell.
     *
     * The module depends on `app:reader` (the composition root that wires the DI graph, navigation
     * host, and theme), `androidx-activity-compose` for the Compose-aware Activity entry point,
     * the Compose UI tooling preview stub for layout inspection in non-debug builds, and the full
     * Compose tooling only in the debug variant.
     */
    private fun Project.configureDependencies() {
        dependencies.add("implementation", project(":app:reader"))
        dependencies.add("implementation", findLibrary("androidx-activity-compose"))
        dependencies.add("implementation", findLibrary("compose-uiToolingPreview"))
        dependencies.add("debugImplementation", findLibrary("compose-uiTooling"))
    }
}
