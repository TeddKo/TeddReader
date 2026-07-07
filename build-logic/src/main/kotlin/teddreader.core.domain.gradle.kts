plugins {
    id("teddreader.koin")
    id("teddreader.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
        }
    }
}
