rootProject.name = "TeddReader"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

includeBuild("build-logic")

include(":androidApp")
include(":baselineprofile")
include(":app:reader")
include(":core:common")
include(":core:domain")
include(":core:data")
include(":core:datastore")
include(":core:room")
include(":core:designsystem")
include(":core:ui")
include(":feature:home:api")
include(":feature:home:impl")

include(":feature:reader:api")
include(":feature:settings:api")
include(":feature:search:api")
include(":feature:bookmarks:api")
include(":feature:document-info:api")
include(":feature:reader:impl")
include(":feature:settings:impl")
include(":feature:search:impl")
include(":feature:bookmarks:impl")
include(":feature:document-info:impl")
