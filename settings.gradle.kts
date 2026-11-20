pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.glass-launcher.net/babric")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.ornithemc.net/releases")
        maven("https://maven.ornithemc.net/snapshots")
        // Central's filtered mirror (#1453): org.jacoco plugin dependencies are resolved here first,
        // so a 429 from Central cannot fail plugin resolution. Every other group is asked only of
        // Central.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content {
                includeGroup("org.jacoco")
            }
        }
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
    "1.2.5-forge",
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
    include("loaders:1.2.5-forge")
    include("loaders:b1.7.3-ornithe")
    include("loaders:b1.7.3-stationapi")
    include("loaders:a1.1.2_01-ornithe")

    for (loader in standaloneLoaders) {
        project(":loaders:$loader").buildFileName = "root.gradle"
    }
}
