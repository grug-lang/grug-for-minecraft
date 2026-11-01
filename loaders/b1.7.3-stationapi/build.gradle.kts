import java.io.File

plugins {
    id("fabric-loom") version "1.16.3"
    id("babric-loom-extension") version "1.16.1"
}

apply(from = "../../gradle/jacoco-loader.gradle")

// The hand-off from core: the repository this resolves it from and the version it asks for both come
// from gradle/grug-core.gradle, which core publishes under too.
apply(from = "../../gradle/grug-core.gradle")

val grugCoreVersion = project.property("grugCoreVersion") as String
val grugCoreRepository = project.property("grugCoreRepository") as File

java.sourceCompatibility = JavaVersion.VERSION_17
java.targetCompatibility = JavaVersion.VERSION_17

base.archivesName = project.properties["archives_base_name"] as String
version = project.property("grugVersion") as String
group = project.properties["maven_group"] as String

loom {
    runs {
        register("testClient") {
            source("test")
            client()
        }
        register("testServer") {
            source("test")
            server()
        }
    }
}

repositories {
    // The only place net.grug is looked up: without this Gradle asks every other repository for
    // grug-core too, and a rate limit from one of them (#433) fails a build that has the jar.
    exclusiveContent {
        forRepository {
            maven {
                name = "grugBuild"
                url = grugCoreRepository.toURI()
            }
        }
        filter {
            includeGroup("net.grug")
        }
    }

    maven("https://maven.glass-launcher.net/snapshots/")
    maven("https://maven.glass-launcher.net/releases/")
    maven("https://maven.glass-launcher.net/babric")
    maven("https://maven.minecraftforge.net/")
    maven("https://jitpack.io/")
    mavenCentral()

    exclusiveContent {
        forRepository {
            maven("https://api.modrinth.com/maven")
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    // Only to compile and run in development: in the universal jar, this layer finds core at the
    // jar's root, which holds it once for every layer, with gson relocated so grug never shares gson
    // with StationAPI or any other mod.
    implementation("net.grug:grug-core:$grugCoreVersion")

    minecraft("com.mojang:minecraft:b1.7.3")
    mappings("net.glasslauncher:biny:${project.properties["yarn_mappings"]}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.properties["loader_version"]}")

    // slf4j-api 2.x needs the 2.0 Log4j bridge. The 1.8 bridge implements the SLF4J 1.8
    // SLF4JServiceProvider, whose misspelled getRequesteApiVersion() the 2.x version sanity check
    // calls as getRequestedApiVersion(), throwing AbstractMethodError. log4j-slf4j2-impl tracks
    // log4j-core, so both stay on the same version.
    implementation("org.apache.logging.log4j:log4j-core:2.26.1")
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.26.1")

    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")

    modImplementation("net.modificationstation:StationAPI:${project.properties["stationapi_version"]}")
    modImplementation("net.glasslauncher.mods:GlassConfigAPI:${project.properties["gcapi_version"]}")
    modImplementation("net.danygames2014:modmenu:${project.properties["modmenu_version"]}")
    // AlwaysMoreItems is deliberately absent: it draws 3D item cubes over the inventory's item
    // icons, so a dev runtime that loaded it drew a different item list from an installed game,
    // which loads only this mod's declared dependencies. The port never called it, and the JEI
    // screenshot test pins the installed rendering (mods/jei/tests/item_list-Test.grug).
}

configurations.all {
    exclude("babric")
}

tasks.withType<ProcessResources> {
    inputs.property("version", project.properties["version"])
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.properties["version"]))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // CI compiles from a fresh checkout, so incremental compilation has no previous output to be
    // incremental against and still snapshots its whole compile classpath, the Minecraft jar most of
    // all. A local build keeps it, where the previous output is still there. See #702.
    if (System.getenv("GRUG_CI") == "true") {
        options.isIncremental = false
    }
}

java {
    withSourcesJar()
}

tasks.withType<Jar> {
    from("../../LICENSE") { // Updated path assuming root contains LICENSE
        rename { "${it}_${project.properties["archivesBaseName"]}" }
    }
}

// This loader's layer of the universal jar, which nests it under META-INF/jars/. Copied to
// build/layer/ so every loader's layer is found at the same path.
tasks.register<Copy>("layerJar") {
    from(tasks.named("remapJar"))
    into(layout.buildDirectory.dir("layer"))
    rename { "grug-${project.projectDir.name}.jar" }
}
