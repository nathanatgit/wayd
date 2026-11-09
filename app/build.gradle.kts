import java.util.Properties

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
        versionCode = 3
        versionName = "0.1.1"
        // WAYD's native libraries are built for 64-bit ARM phones only.
        ndk { abiFilters += "arm64-v8a" }
        (project.findProperty("appIdSuffix") as String?)?.let { applicationIdSuffix = it }
    }

    flavorDimensions += "dist"
    productFlavors {
        // D-006 / D-027: `standard` has no MITM code; `research` adds it. Releases ship `research` under the plain application id
        // (no suffix, no "-research" in the version). `-PappIdSuffix=.x` installs a build next to another one for testing.
        create("standard") {
            dimension = "dist"
        }
        create("research") {
            dimension = "dist"
        }
    }

    // Release signing: from keystore.properties (git-ignored, local) or WAYD_* environment variables (CI secrets).
    val signing = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signingValue(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)
    val releaseStoreFile = signingValue("storeFile", "WAYD_KEYSTORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "WAYD_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "WAYD_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "WAYD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") // unsigned when no key is configured
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
