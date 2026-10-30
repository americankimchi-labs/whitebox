plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin, zero Android deps.
kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}
