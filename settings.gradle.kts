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
        // Vendored Godot Android library (see runtime/godot/repo/README.md) — AGP forbids a direct
        // local .aar file dependency inside a module that itself produces an AAR, so it's resolved
        // as a normal Maven artifact from this local repo instead of `files(...)`. Make the source
        // exclusive: Maven Central publishes the same godot-tools coordinate, but that stock binary
        // was built with path overrides disabled and silently won resolution when Central came first.
        exclusiveContent {
            forRepository {
                maven { url = uri(File(rootDir, "runtime/godot/repo")) }
            }
            filter { includeGroup("org.godotengine") }
        }
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
include(":infra:p2p")
include(":runtime:node")
include(":runtime:godot")
include(":runtime:godot-editor")
include(":runtime:buildtools")
