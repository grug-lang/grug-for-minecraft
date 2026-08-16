import org.gradle.internal.extensions.stdlib.toDefaultLowerCase
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.URL

plugins {
	id("maven-publish")
	id("fabric-loom") version "1.15.3"
	id("babric-loom-extension") version "1.15.3"
}

//noinspection GroovyUnusedAssignment
java.sourceCompatibility = JavaVersion.VERSION_17
java.targetCompatibility = JavaVersion.VERSION_17

base.archivesName = project.properties["archives_base_name"] as String
version = project.properties["mod_version"] as String
group = project.properties["maven_group"] as String

loom {
//	accessWidenerPath = file("src/main/resources/examplemod.accesswidener")

	runs {
		// If you want to make a testmod for your mod, right click on src, and create a new folder with the same name as source() below.
		// Intellij should give suggestions for testmod folders.
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
		forRepository {
			maven("https://api.modrinth.com/maven")
		}
		filter {
			includeGroup("maven.modrinth")
		}
	}
}

dependencies {
	minecraft("com.mojang:minecraft:b1.7.3")
	mappings("net.glasslauncher:biny:${project.properties["yarn_mappings"]}:v2")
	modImplementation("net.fabricmc:fabric-loader:${project.properties["loader_version"]}")

	implementation("org.apache.logging.log4j:log4j-core:2.17.2")

	implementation("org.slf4j:slf4j-api:1.8.0-beta4")
	implementation("org.apache.logging.log4j:log4j-slf4j18-impl:2.17.1")

	// convenience stuff
	// adds some useful annotations for data classes. does not add any dependencies
	compileOnly("org.projectlombok:lombok:1.18.42")
	annotationProcessor("org.projectlombok:lombok:1.18.42")

	// adds some useful annotations for miscellaneous uses. does not add any dependencies, though people without the lib will be missing some useful context hints.
	implementation("org.jetbrains:annotations:23.0.0")
	implementation("com.google.guava:guava:33.2.1-jre")

	// StAPI itself.
	// transitiveImplementation tells babric loom that you want this dependency to be pulled into other mod's development workspaces. Best used ONLY for required dependencies.
	modImplementation("net.modificationstation:StationAPI:${project.properties["stationapi_version"]}")

	// Extra mods.
	// https://github.com/calmilamsy/glass-config-api
	modImplementation("net.glasslauncher.mods:GlassConfigAPI:${project.properties["gcapi_version"]}")
	// https://github.com/calmilamsy/modmenu
	modImplementation("net.danygames2014:modmenu:${project.properties["modmenu_version"]}")
	// https://github.com/Glass-Series/Always-More-Items
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

// ensure that the encoding is set to UTF-8, no matter what the system default is
// this fixes some edge cases with special characters not displaying correctly
// see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()
}

tasks.withType<Jar> {
	from("LICENSE") {
		rename { "${it}_${project.properties["archivesBaseName"]}" }
	}
}

// Tells gradle to not generate module files for maven.
// They aren't standard and the documentation is abysmal. Stop it.
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

