plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Plain JVM so the event store and hash chain test fast without a device.
// Android Keystore signing lives in :app behind the Signer interface.
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":vehicle-core"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":vehicle-fake")) // the demo drive, replayed end to end
}
