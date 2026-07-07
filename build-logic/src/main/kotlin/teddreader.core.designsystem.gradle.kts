plugins {
    id("teddreader.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
        }
    }
}
