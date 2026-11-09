plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nathanhanapps.wayd.analysis"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:db"))
    api(project(":enrich"))
    api(project(":collectors"))
    testImplementation(libs.junit)
}
