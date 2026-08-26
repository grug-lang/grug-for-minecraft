import org.gradle.internal.extensions.stdlib.toDefaultLowerCase
import java.io.BufferedReader
import java.io.File
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
//	accessWidenerPath = file("src/main/resources/grug.accesswidener")

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

// Define the generated resources directory inside the build folder
val generatedResourcesDir = layout.buildDirectory.dir("generated/resources").get().asFile
val nativesOutDir = generatedResourcesDir.resolve("natives")

// Add the generated directory to the main source set so it gets included in the JAR and classpath
sourceSets.main.get().resources.srcDir(generatedResourcesDir)

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

fun runCommand(command: List<String>, workingDir: File? = null) {
	val process = ProcessBuilder(command)
		.apply { if (workingDir != null) directory(workingDir) }
		.redirectErrorStream(true)
		.start()
	val output = process.inputStream.bufferedReader().readText()
	val exitCode = process.waitFor()
	println(output)
	if (exitCode != 0) {
		throw GradleException("Command failed with exit code $exitCode: ${command.joinToString(" ")}\n$output")
	}
}

val grugRsDir = layout.buildDirectory.dir("grug-rs").get().asFile
val grugRsBranch = "grug-for-minecraft-fixes"

val cloneGrugRs = tasks.register("cloneGrugRs") {
	doLast {
		if (!grugRsDir.resolve(".git").exists()) {
			runCommand(listOf("git", "clone", "--branch", grugRsBranch, "https://github.com/grug-lang/grug-rs.git", grugRsDir.absolutePath))
		} else {
			runCommand(listOf("git", "fetch", "origin", grugRsBranch), grugRsDir)
			runCommand(listOf("git", "checkout", grugRsBranch), grugRsDir)
			runCommand(listOf("git", "reset", "--hard", "origin/$grugRsBranch"), grugRsDir)
		}
	}
}

val buildGrugRs = tasks.register("buildGrugRs") {
	dependsOn(cloneGrugRs)
	val libFile = grugRsDir.resolve("target/release/libgruggers.a")
	outputs.file(libFile)
	outputs.upToDateWhen { false } // Let Cargo manage its own cache natively
	doLast {
		runCommand(listOf("cargo", "build", "--release", "-p", "gruggers"), grugRsDir)
	}
}

val nativeSrcDir = file("src/main/native")

val generateGrugAdapter = tasks.register("generateGrugAdapter") {
	val modApiJson = file("src/main/resources/mod_api.json")
	val generatorScript = file("generate.py")
	val generatedC = layout.buildDirectory.dir("generated/grug").get().asFile.resolve("adapter_generated.c")
	inputs.file(modApiJson)
	inputs.file(generatorScript)
	outputs.file(generatedC)
	doLast {
		generatedC.parentFile.mkdirs()
		runCommand(listOf("python3", generatorScript.absolutePath, modApiJson.absolutePath, generatedC.absolutePath))
	}
}

val buildGrugAdapter = tasks.register("buildGrugAdapter") {
	dependsOn(buildGrugRs, generateGrugAdapter)
	val adapterC = nativeSrcDir.resolve("adapter.c")
	val generatedC = generateGrugAdapter.get().outputs.files.singleFile
	val libGruggers = grugRsDir.resolve("target/release/libgruggers.a")
	val outLib = nativesOutDir.resolve("libadapter.so")

	inputs.file(adapterC)
	inputs.file(generatedC)
	inputs.file(nativeSrcDir.resolve("adapter_shared.h"))
	inputs.file(libGruggers)
	outputs.file(outLib)

	doLast {
		nativesOutDir.mkdirs()
		val javaHome = System.getProperty("java.home")
		runCommand(
			listOf(
				"cc", "-shared", "-fPIC",
				"-I$javaHome/include", "-I$javaHome/include/linux",
				"-I${nativeSrcDir.absolutePath}",
				adapterC.absolutePath,
				generatedC.absolutePath,
				libGruggers.absolutePath,
				"-o", outLib.absolutePath,
				"-ldl", "-lpthread", "-lm"
			)
		)
	}
}

tasks.named("processResources") {
	dependsOn(buildGrugAdapter)
}
