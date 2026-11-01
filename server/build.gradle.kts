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
    implementation(libs.google.cloud.firestore) // the store on Cloud Run (WHITEBOX_STORE=firestore)
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
// A new value for the deployment's server-key secret (printed once; store it in Secret Manager).
tasks.register<JavaExec>("newServerKey") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.americankimchilabs.whitebox.server.NewServerKeyKt")
}

tasks.register<JavaExec>("demoCar") {
    group = "application"
    description = "Drives the scripted demo drive into the mirror at ws://localhost:8080 (crash, then power loss)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.americankimchilabs.whitebox.server.DemoCarKt")
    workingDir = rootProject.projectDir
}

// The phone pages' JavaScript, tested against vectors the Kotlin tests write from the car's own code (Node 18+,
// no packages). Runs after `test`; skipped with a notice where Node is not installed.
val webTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the viewer's JavaScript tests (src/test/web) against build/web-vectors."
    dependsOn(tasks.named("test"))
    workingDir = projectDir
    commandLine("node", "--test", "src/test/web/*.test.js")
    onlyIf {
        val node = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        if (!node) logger.warn("webTest skipped: Node.js is not installed (the viewer's JavaScript tests need it).")
        node
    }
}
tasks.named("test") { finalizedBy(webTest) }
