pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "folio"
include(":core")
include(":app")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
