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

rootProject.name = "OrionAndroid"

include(":app")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:design")
include(":engine:qnn")
include(":engine:mnn")
include(":engine:providers")
include(":engine:server")
include(":engine:agent")
include(":infra:git")
include(":runtime:node")
include(":runtime:buildtools")
