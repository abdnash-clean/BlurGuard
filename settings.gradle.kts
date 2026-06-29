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
    }
}

rootProject.name = "BlurGuard"

// App
include(":app")

// Core
include(":core:common")
include(":core:model")
include(":core:camera")
include(":core:blurring")
include(":core:ml")
include(":core:tracking")
include(":core:recognition")
include(":core:domain")
include(":core:data")
include(":core:designsystem")

// Features
include(":feature:camera")
include(":feature:gallery")
include(":feature:settings")

// Benchmarks
include(":benchmark")
