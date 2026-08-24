package buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a module imports Material 3 outside the two places allowed to.
 *
 * The app wraps every Material component it uses behind its own design system, so that colour,
 * shape, type and ripple all come from the app's tokens rather than Material's defaults. That policy
 * only holds while no screen can reach `androidx.compose.material3` directly — one stray import and a
 * Material default is back in the UI, looking close enough to correct that review misses it. This task
 * is what turns the policy from a habit into a build failure.
 *
 * Written as a task class rather than inline in a `.gradle.kts` script for the configuration cache: a
 * `doLast { }` block in a script body captures the enclosing script object, which Gradle cannot
 * serialize, so the whole build fails with "cannot serialize Gradle script object references". Every
 * value this task needs therefore arrives through a declared input property instead.
 */
@CacheableTask
abstract class Material3ImportCheckTask : DefaultTask() {

    /**
     * The Gradle path of the module being checked, such as `:feature:home:impl`.
     *
     * Both an input for up-to-date checks and the identity used to decide whether this module is one of
     * [fullyAllowedModulePaths], so a module that is renamed is re-checked under its new rules.
     */
    @get:Input
    abstract val modulePath: Property<String>

    /**
     * Module paths where any Material 3 import is permitted.
     *
     * These are the modules whose job is to wrap Material: the design system, which hands the app's
     * colours and shapes to `MaterialTheme`, and the shared UI module, which owns the wrappers around
     * the components the app keeps from Material.
     */
    @get:Input
    abstract val fullyAllowedModulePaths: SetProperty<String>

    /**
     * The only Material 3 symbols every other module may import.
     *
     * A short, deliberate exception list rather than a general escape hatch — each entry is a component
     * whose platform behaviour is not worth reimplementing behind a wrapper.
     */
    @get:Input
    abstract val allowedSymbols: SetProperty<String>

    /**
     * Every Kotlin source file in the module, across all source sets.
     *
     * Test sources are included on purpose: a test that imports Material directly bypasses the wrapper
     * layer exactly as production code would, and would then assert against Material's defaults rather
     * than the app's tokens.
     *
     * `@PathSensitive(RELATIVE)` so the check stays up to date when the checkout moves. `@SkipWhenEmpty`
     * makes Gradle skip the task for a module with no sources at all instead of reporting a failure that
     * says nothing — a module that genuinely has sources but resolves to an empty set fails inside
     * [check] instead, because that means the wiring is wrong rather than that there is nothing to scan.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:SkipWhenEmpty
    abstract val sourceFiles: ConfigurableFileCollection

    /**
     * A file written on success, so Gradle can mark the task up to date.
     *
     * A verification task has no real output; without one, Gradle would re-scan every source file on
     * every build. The marker's absence after a failed run is what makes the next run re-check.
     */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    /**
     * Scans every source file and fails with the full list of offending imports.
     *
     * Reports all violations at once rather than stopping at the first, because these arrive in batches
     * — a screen that reached for Material usually did so several times — and fixing them one build at a
     * time is needless work.
     *
     * @throws GradleException when a disallowed import is found, or when the module has sources on disk
     * but none reached this task, which means the source wiring is broken rather than clean.
     */
    @TaskAction
    fun check() {
        val path = modulePath.get()
        val isFullyAllowed = path in fullyAllowedModulePaths.get()
        val permitted = allowedSymbols.get()
        val files = sourceFiles.files.filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }

        if (files.isEmpty()) {
            throw GradleException(
                "checkMaterial3Imports [$path]: no Kotlin sources reached the task. " +
                    "The source wiring is broken — this is not a clean pass.",
            )
        }

        logger.lifecycle("checkMaterial3Imports [$path]: scanning ${files.size} .kt file(s)")

        if (isFullyAllowed) {
            writeMarker()
            return
        }

        val importPattern = Regex("""^import\s+androidx\.compose\.material3\.(\S+)""")
        val violations = mutableListOf<String>()

        files.forEach { file ->
            file.bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val symbol = importPattern.find(line.trimStart())
                        ?.groupValues
                        ?.get(1)
                        ?.trimEnd(';', ' ')
                        ?: return@forEachIndexed
                    if (symbol !in permitted) {
                        violations += "${file.path}:${index + 1}: $symbol"
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Material 3 import policy violated in $path (${violations.size}).")
                    appendLine()
                    appendLine("androidx.compose.material3 may only be imported in:")
                    fullyAllowedModulePaths.get().sorted().forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Every other module may import only:")
                    permitted.sorted().forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Offending imports:")
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    append("Wrap the component in :core:ui and expose it through the design system.")
                },
            )
        }

        writeMarker()
    }

    /**
     * Records that the module passed, so an unchanged module is not scanned again.
     */
    private fun writeMarker() {
        val file = marker.get().asFile
        file.parentFile?.mkdirs()
        file.writeText("ok\n")
    }
}
