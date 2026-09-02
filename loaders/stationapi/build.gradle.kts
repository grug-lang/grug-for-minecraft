import java.net.URI

plugins {
    id("maven-publish")
    id("fabric-loom") version "1.15.3"
    id("babric-loom-extension") version "1.15.3"
}

java.sourceCompatibility = JavaVersion.VERSION_17
java.targetCompatibility = JavaVersion.VERSION_17

base.archivesName = project.properties["archives_base_name"] as String
version = project.properties["mod_version"] as String
group = project.properties["maven_group"] as String

loom {
    runs {
        register("testClient") {
            source("test")
            client()
            configurations.transitiveImplementation
        }
        register("testServer") {
            source("test")
            server()
            configurations.transitiveImplementation
        }
    }
}

repositories {
    maven("https://maven.glass-launcher.net/snapshots/")
    maven("https://maven.glass-launcher.net/releases/")
    maven("https://maven.glass-launcher.net/babric")
    maven("https://maven.minecraftforge.net/")
    maven("https://jitpack.io/")
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    // 1. Depend on core logic
    implementation(project(":core"))
    // 2. Bundle core classes and native libs into final mod jar
    include(project(":core"))

    minecraft("com.mojang:minecraft:b1.7.3")
    mappings("net.glasslauncher:biny:${project.properties["yarn_mappings"]}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.properties["loader_version"]}")

    implementation("org.apache.logging.log4j:log4j-core:2.17.2")
    implementation("org.slf4j:slf4j-api:1.8.0-beta4")
    implementation("org.apache.logging.log4j:log4j-slf4j18-impl:2.17.1")

    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    modImplementation("net.modificationstation:StationAPI:${project.properties["stationapi_version"]}")
    modImplementation("net.glasslauncher.mods:GlassConfigAPI:${project.properties["gcapi_version"]}")
    modImplementation("net.danygames2014:modmenu:${project.properties["modmenu_version"]}")
    modImplementation("net.glasslauncher.mods:AlwaysMoreItems:${project.properties["alwaysmoreitems_version"]}")
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
}

java {
    withSourcesJar()
}

tasks.withType<Jar> {
    from("../../LICENSE") { // Updated path assuming root contains LICENSE
        rename { "${it}_${project.properties["archivesBaseName"]}" }
    }
}

tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

publishing {
    repositories {
        mavenLocal()
        if (project.hasProperty("my_maven_username")) {
            maven {
                url = URI("https://maven.example.com")
                credentials {
                    username = "${project.properties["my_maven_username"]}"
                    password = "${project.properties["my_maven_password"]}"
                }
            }
        }
    }

    publications {
        register("mavenJava", MavenPublication::class) {
            artifactId = project.properties["archives_base_name"] as String
            from(components["java"])
        }
    }
}
