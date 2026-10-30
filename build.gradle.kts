plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Hard rule: nothing outside :vehicle-pleos may reference a Pleos class.
// A plain source scan instead of a lint/Konsist dependency; wired into every module's `check`.
val checkPleosImports by tasks.registering {
    group = "verification"
    description = "Fails if any module other than :vehicle-pleos references ai.pleos.*"
    val sources = fileTree(rootDir) {
        include("*/src/**/*.kt", "*/src/**/*.java")
        exclude("vehicle-pleos/**", "vendor/**", "**/build/**")
    }
    inputs.files(sources)
    doLast {
        val pleosRef = Regex("""\bai\.pleos\.""")
        val offenders = sources.files.filter { pleosRef.containsMatchIn(it.readText()) }
        check(offenders.isEmpty()) {
            "Pleos classes referenced outside :vehicle-pleos:\n" +
                offenders.joinToString("\n") { "  " + it.relativeTo(rootDir) }
        }
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(checkPleosImports) }
}
