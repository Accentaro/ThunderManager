pluginManagement {
    repositories {
        google()
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

rootProject.name = "ThunderManager"

include(":apps:manager")
include(":android:package-inspector")
include(":android:patch-domain")
include(":android:injection-api")
include(":android:injection-custom")
include(":android:bootstrap")
include(":android:signing")
include(":android:package-installer")
include(":android:patch-orchestrator")
include(":android:update-client")
