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

project(":loaders:b1.7.3-ornithe").buildFileName = "root.gradle"
