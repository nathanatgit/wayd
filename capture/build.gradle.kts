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
    implementation(project(":enrich"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

// ---- Native netstack (Rust, see capture/native) -------------------------------------------------
// Two variants of libwayd_net.so are built with cargo-ndk:
//   standard: no TLS interception code at all;  research: built with `--features mitm` (D-006, AGENTS.md rule 7).
// Each goes to its own folder under build/native/<variant>; the :app module packages the matching one per flavor.
// Needs: rustup + Android targets, cargo-ndk, and the NDK declared above. Pass -PskipNative to reuse built libraries.
val nativeAbis = listOf("arm64-v8a")
val ndkDir = project.extensions.getByType<com.android.build.api.variant.LibraryAndroidComponentsExtension>()
    .sdkComponents.ndkDirectory

fun registerNative(variant: String, extraArgs: List<String>) = tasks.register<Exec>("buildNative${variant.replaceFirstChar { it.uppercase() }}") {
    group = "build"
    description = "Builds the Rust netstack ($variant) with cargo-ndk"
    val nativeDir = layout.projectDirectory.dir("native")
    val outDir = layout.buildDirectory.dir("native/$variant")
    workingDir = nativeDir.asFile
    inputs.dir(nativeDir.dir("src"))
    inputs.file(nativeDir.file("Cargo.toml"))
    inputs.file(nativeDir.file("Cargo.lock"))
    outputs.dir(outDir)
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
    // Separate cargo target dirs so switching variants does not rebuild everything.
    environment("CARGO_TARGET_DIR", nativeDir.dir("target-$variant").asFile.absolutePath)
    doFirst { environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath) }
    val cargoName = if (OperatingSystem.current().isWindows) "cargo.exe" else "cargo"
    val cargoHome = File("$home/.cargo/bin/$cargoName")
    val cargo = if (cargoHome.exists()) cargoHome.absolutePath else cargoName
    val abiArgs = nativeAbis.flatMap { listOf("-t", it) }
    commandLine(
        listOf(cargo, "ndk") + abiArgs +
            listOf("-P", "29", "-o", outDir.get().asFile.absolutePath, "build", "--release") + extraArgs,
    )
}

registerNative("standard", emptyList())
registerNative("research", listOf("--features", "mitm"))
