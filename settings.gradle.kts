pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content { includeGroupAndSubgroups("ai.pleos.playground") }
        }
    }
}

rootProject.name = "WhiteBox"

include(":domain")
include(":vehicle-core")
include(":data")
include(":vehicle-fake")
include(":server")

// The car app needs the Android SDK; the mirror server, the simulated car and the tamper demo need only a JDK.
// Without an SDK (ANDROID_HOME, ANDROID_SDK_ROOT or sdk.dir in local.properties) only the JVM modules are built.
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }?.let { f ->
        java.util.Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }
if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":vehicle-pleos")
    include(":app")
} else {
    println("No Android SDK found: building the JVM modules only (server, demo car, tamper demo).")
}

// Judge-runnable CLI: ./gradlew -q :tamper-demo:run
include(":tamper-demo")
project(":tamper-demo").projectDir = file("tools/tamper-demo")
