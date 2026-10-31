plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The cloud mirror: ingest, server-attested seals, dead-man switch, dashboard. Plain JVM, reusing :data for the
// wire protocol and chain checks. Storage and notification sit behind interfaces so it can move to managed services.
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":data"))
    implementation(project(":vehicle-core"))
    implementation(project(":vehicle-fake")) // the demo drive, for the simulated car
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("com.americankimchilabs.whitebox.server.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// A simulated car that drives the demo drive into a running mirror (no emulator needed).
tasks.register<JavaExec>("demoCar") {
    group = "application"
    description = "Drives the scripted demo drive into the mirror at ws://localhost:8080 (crash, then power loss)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.americankimchilabs.whitebox.server.DemoCarKt")
    workingDir = rootProject.projectDir
}
