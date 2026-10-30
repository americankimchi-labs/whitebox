plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Ports only. Domain types appear in port signatures, so :domain is exposed as api.
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":domain"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
