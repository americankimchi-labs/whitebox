plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Runs the product's own verification code (:data) against a copy of a recorded chain, untouched vs one byte changed.
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":data"))
    implementation(project(":vehicle-core")) // domain types
}

application {
    mainClass.set("com.americankimchilabs.whitebox.tamper.TamperDemoKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir // relative chain paths resolve from the repo root
}
