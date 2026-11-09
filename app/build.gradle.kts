plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nathanhanapps.wayd"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nathanhanapps.wayd"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        // WAYD's native libraries are built for 64-bit ARM phones only.
        ndk { abiFilters += "arm64-v8a" }
    }

    flavorDimensions += "dist"
    productFlavors {
        // D-006: `standard` has no MITM / root plaintext; `research` adds them (M8).
        create("standard") {
            dimension = "dist"
        }
        create("research") {
            dimension = "dist"
            applicationIdSuffix = ".research"
            versionNameSuffix = "-research"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        // Each flavor packages its own native library: `research` includes the TLS-interception engine, `standard` does not.
        getByName("standard").jniLibs.srcDir(rootProject.file("capture/build/native/standard"))
        getByName("research").jniLibs.srcDir(rootProject.file("capture/build/native/research"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:db"))
    implementation(project(":core:ui"))
    implementation(project(":capture"))
    implementation(project(":analysis"))
    implementation(project(":collectors"))
    implementation(project(":privilege"))
    implementation(project(":ai"))
    implementation(project(":enrich"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

// The native libraries are produced by :capture; make sure they exist before they are merged into an APK.
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        when {
            name.contains("Standard") -> dependsOn(":capture:buildNativeStandard")
            name.contains("Research") -> dependsOn(":capture:buildNativeResearch")
        }
    }
}
