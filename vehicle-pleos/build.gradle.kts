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
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
