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
include(":vehicle-pleos")
include(":app")

// Judge-runnable CLI: ./gradlew -q :tamper-demo:run
include(":tamper-demo")
project(":tamper-demo").projectDir = file("tools/tamper-demo")
