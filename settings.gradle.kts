// Aron: ONE root Gradle build for shared, db, backend and android (docs/24-build-spec.md s1 and s2).
// The web app (web/, Next.js) and the contract linter (contract/, Redocly) are not Gradle projects.
// Owner: infra lane. Adding a module = a PR that edits this file and the module table in docs/24 s2.1.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Maven Central mirror (owner-approved 2026-10-07, DECISIONS.md): lane and developer containers share one outgoing IP
        // that Maven Central rate-limits (HTTP 429). The mirror is Google's official copy of Central, tried first
        // everywhere EXCEPT GitHub Actions, so CI always resolves from the real Central (the independent check).
        // Set ARON_NO_MIRROR=1 to turn it off.
        if (System.getenv("GITHUB_ACTIONS") == null && System.getenv("ARON_NO_MIRROR") == null) {
            maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Same mirror rule as in pluginManagement above.
        if (System.getenv("GITHUB_ACTIONS") == null && System.getenv("ARON_NO_MIRROR") == null) {
            maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        }
        mavenCentral()
    }
}

rootProject.name = "aron"

// Shared Kotlin Multiplatform modules (phone + server). Lane: contract/shared.
include(":shared:contract")
include(":shared:rules")

// Database migrations packaged for Flyway. Lane: db.
include(":db")

// Backend (Ktor). Lane: backend. One deployable image, two roles (api, worker): backend:app.
include(":backend:platform")
include(":backend:auth")
include(":backend:sync")
include(":backend:masterdata")
include(":backend:config")
include(":backend:analytics")
include(":backend:notify")
include(":backend:media")
include(":backend:app")

// Android (native Kotlin, Jetpack Compose). Lane: android.
include(":android:core-common")
include(":android:core-database")
include(":android:core-network")
include(":android:core-session")
include(":android:core-sync")
include(":android:core-geo")
include(":android:core-printing")
include(":android:core-ui")
include(":android:dpc")
include(":android:feature-auth")
include(":android:feature-home")
include(":android:feature-attendance")
include(":android:feature-stock")
include(":android:feature-sale")
include(":android:feature-memo")
include(":android:feature-dayclose")
include(":android:feature-outlet")
include(":android:feature-tasks")
include(":android:feature-amo")
include(":android:feature-tso")
include(":android:app-sr")
include(":android:app-amo")
include(":android:app-tso")
// Lane android-core (AUD-PERF-04): macrobenchmark cold start and baseline-profile generator; runs on a device or emulator.
include(":android:benchmark")
// Lane android-sys: camera, photo pipeline and media queue; permissions, update, support upload, language and logout.
include(":android:core-system")
include(":android:core-media")

// Lane android-core-ui: Roborazzi screenshot tests of the core screens (test scope only, no APK cost).
include(":android:ui-screenshots")
