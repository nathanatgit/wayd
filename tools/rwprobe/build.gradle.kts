plugins {
    id("com.android.application") version "9.5.1"
}

android {
    namespace = "com.nathanhanapps.wayd.rwprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.nathanhanapps.wayd.rwprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
