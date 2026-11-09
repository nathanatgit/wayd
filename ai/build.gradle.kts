plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nathanhanapps.wayd.ai"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:db"))
    // On-device runtime for Gemma `.litertlm` models (Apache 2.0). Brings its own native library.
    api(libs.litertlm.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    // The Android stub of org.json throws in plain JVM tests.
    testImplementation(libs.org.json)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
