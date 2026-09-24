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

val activeLoader = System.getProperty("grug.activeLoader")

// These loaders use their own Gradle wrapper because their build plugins
// require a different Gradle version from the root Forge build.
val standaloneLoaders = setOf(
    "b1.7.3-ornithe",
    "a1.1.2_01-ornithe",
    "b1.7.3-stationapi",
)

if (activeLoader != null) {
    include("loaders:$activeLoader")

    if (activeLoader in standaloneLoaders) {
        project(":loaders:$activeLoader").buildFileName = "root.gradle"
    }
} else {
    include("loaders:1.20.6-forge")
    include("loaders:b1.7.3-ornithe")
    include("loaders:b1.7.3-stationapi")
    include("loaders:a1.1.2_01-ornithe")

    for (loader in standaloneLoaders) {
        project(":loaders:$loader").buildFileName = "root.gradle"
    }
}
