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
include(":app")
include(":core:data")
include(":core:designsystem")
include(":core:model")
include(":core:camera")
include(":core:ml")
include(":core:tracking")
include(":core:blurring")
include(":feature:camera")
include(":feature:settings")
