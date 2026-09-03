pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.glass-launcher.net/babric")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.ornithemc.net/releases")
        maven("https://maven.ornithemc.net/snapshots")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "grug-for-minecraft"

include("core")

include("loaders:1.20.6-forge")
include("loaders:b1.7.3-ornithe")
include("loaders:b1.7.3-stationapi")
include("loaders:a1.1.2_01-ornithe")

// These are projects, as they require Gradle 9 rather than 8.
project(":loaders:b1.7.3-ornithe").buildFileName = "root.gradle"
project(":loaders:a1.1.2_01-ornithe").buildFileName = "root.gradle"
