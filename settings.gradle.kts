pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "quire"
include(":core")
include(":app")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
