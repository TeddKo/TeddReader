import buildlogic.Material3ImportCheckTask

// Material 3 import policy gate.
//
// The app wraps every Material component it keeps behind its own design system, so colour, shape, type
// and ripple all come from the app's tokens rather than Material's defaults. That only holds while
// nothing else can import androidx.compose.material3 directly, so this registers a verification task
// per module and hangs it off `check`. Applied transitively through teddreader.kmp.compose, which every
// Compose-using module already applies.
//
// The scanning logic lives in buildlogic.Material3ImportCheckTask rather than in a doLast block here:
// a lambda in a script body captures the enclosing script object, which the configuration cache cannot
// serialize, and the build fails with "cannot serialize Gradle script object references".

val checkMaterial3Imports = tasks.register<Material3ImportCheckTask>("checkMaterial3Imports") {
    group = "verification"
    description = "Fails when androidx.compose.material3 is imported outside the modules allowed to."

    modulePath.set(project.path)

    // The two modules whose job is wrapping Material: designsystem hands the app's colours and shapes
    // to MaterialTheme, and core:ui owns the wrappers around the components the app keeps.
    fullyAllowedModulePaths.set(setOf(":core:ui", ":core:designsystem"))

    // The reader's table-of-contents drawer delegates its swipe gesture, back handling and focus trap
    // to the platform, has a single usage site, and gains nothing from a wrapper. A deliberate
    // exception — extending this list is a design decision, not a convenience.
    allowedSymbols.set(
        setOf(
            "DrawerValue",
            "ModalDrawerSheet",
            "ModalNavigationDrawer",
            "NavigationDrawerItem",
            "rememberDrawerState",
        ),
    )

    sourceFiles.from(
        project.layout.projectDirectory.dir("src").asFileTree.matching { include("**/*.kt") },
    )

    marker.set(project.layout.buildDirectory.file("reports/material3-gate/passed.txt"))
}

// plugins.withId fires only once the base plugin has registered `check`, so this stays safe on a
// module that never gets it.
plugins.withId("org.gradle.base") {
    tasks.named("check") {
        dependsOn(checkMaterial3Imports)
    }
}
