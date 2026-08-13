plugins { id("teddreader.feature.impl") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coil.compose)
        }
    }
}
