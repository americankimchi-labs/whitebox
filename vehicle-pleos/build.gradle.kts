plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The ONLY module allowed to import ai.pleos.* (enforced by :checkPleosImports).
// Also holds the AAOS CarPropertyManager adapter (speed, gear, ignition): both are the real-car edge.
android {
    namespace = "com.americankimchilabs.whitebox.vehicle.pleos"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":vehicle-core"))
    // implementation, not api: Pleos classes must not leak onto :app's compile classpath.
    implementation(libs.vehicle.sdk)
    implementation(libs.navihelper.sdk)
    implementation(libs.stt.sdk)
    implementation(libs.tts.sdk)
    // FusedLocation is not used by the app (it records the platform's GPS); debug builds keep the SDK for on-device
    // checks, so the release APK carries neither the library nor the permissions its manifest adds.
    debugImplementation(libs.fusedlocation.sdk)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
