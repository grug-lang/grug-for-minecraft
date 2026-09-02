pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.glass-launcher.net/babric")
        maven("https://maven.minecraftforge.net/")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "grug-for-minecraft"

include("core")
include("loaders:stationapi")
include("loaders:forge-1.20.6")
