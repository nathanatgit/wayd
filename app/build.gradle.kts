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
        versionCode = 8
        versionName = "0.5.0"
        // WAYD's native libraries are built for 64-bit ARM phones only.
        ndk { abiFilters += "arm64-v8a" }
        (project.findProperty("appIdSuffix") as String?)?.let { applicationIdSuffix = it }
    }

    flavorDimensions += "routing"
    productFlavors {
        // D-049: `core` has no Clash routing; `clash` adds it (app/src/clash, libwayd_route.so). Same application id and signing key, so
        // one variant can be installed over the other. `-PappIdSuffix=.x` installs a build next to another one for testing.
        create("core") {
            dimension = "routing"
        }
        create("clash") {
            dimension = "routing"
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
        // One capture library for every variant (built with the mitm feature, D-048); consent gates decryption at runtime.
        getByName("main").jniLibs.srcDir(rootProject.file("capture/build/native/mitm"))
        // Only `clash` packages the Mihomo transport (libwayd_route.so).
        getByName("clash").jniLibs.srcDir(rootProject.file("routing/build"))
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
    implementation(project(":apkscan"))
    implementation(libs.androidx.work.runtime)

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
        dependsOn(":capture:buildNativeMitm")
        if (name.contains("Clash")) dependsOn(":capture:buildRouting")
    }
}
