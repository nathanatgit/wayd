import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nathanhanapps.wayd.capture"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

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
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

// ---- Native netstack (Rust, see capture/native) -------------------------------------------------
// Builds libwayd_net.so with cargo-ndk into src/main/jniLibs (git-ignored) before every Android build.
// Needs: rustup + Android targets, cargo-ndk, and the NDK declared above. Pass -PskipNative to reuse
// an already-built library.
val nativeAbis = listOf("arm64-v8a")
val ndkDir = project.extensions.getByType<com.android.build.api.variant.LibraryAndroidComponentsExtension>()
    .sdkComponents.ndkDirectory

val buildNative = tasks.register<Exec>("buildNative") {
    group = "build"
    description = "Builds the Rust netstack with cargo-ndk"
    val nativeDir = layout.projectDirectory.dir("native")
    workingDir = nativeDir.asFile
    inputs.dir(nativeDir.dir("src"))
    inputs.file(nativeDir.file("Cargo.toml"))
    inputs.file(nativeDir.file("Cargo.lock"))
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
    onlyIf { !project.hasProperty("skipNative") }

    val home = System.getProperty("user.home")
    val extraPath = buildList {
        add("$home/.cargo/bin")
        // Portable MinGW installed via winget provides the Windows host linker for build scripts.
        file("$home/AppData/Local/Microsoft/WinGet/Packages").listFiles { f -> f.name.startsWith("BrechtSanders.WinLibs") }
            ?.forEach { add("${it.path}/mingw64/bin") }
    }
    val sep = File.pathSeparator
    environment("PATH", extraPath.joinToString(sep) + sep + System.getenv("PATH"))
    doFirst { environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath) }
    val cargoName = if (OperatingSystem.current().isWindows) "cargo.exe" else "cargo"
    val cargoHome = File("$home/.cargo/bin/$cargoName")
    val cargo = if (cargoHome.exists()) cargoHome.absolutePath else cargoName
    val abiArgs = nativeAbis.flatMap { listOf("-t", it) }
    commandLine(
        listOf(cargo, "ndk") + abiArgs +
            listOf("-P", "29", "-o", layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath, "build", "--release"),
    )
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildNative) }
