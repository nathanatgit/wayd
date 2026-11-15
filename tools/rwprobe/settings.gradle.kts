// Standalone project: built with the repository's Gradle wrapper (`./gradlew -p tools/rwprobe assembleDebug`),
// not part of the WAYD build. Keep the plugin version in step with gradle/libs.versions.toml.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "rwprobe"
