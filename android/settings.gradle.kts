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

rootProject.name = "orszem-android"

// Two applications, no shared module. Phase 1 has nothing genuinely shared, and the
// Public and Service apps have deliberately different product identities. A shared
// module is added only when real duplication appears.
include(":public-app")
include(":service-app")
