import java.io.File
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension

plugins {
    id("java-library")
    id("maven-publish")
    id("jacoco")
}

group = providers.gradleProperty("maven_group").get()

// The four loaders that run a Gradle wrapper of their own resolve core from a repository rather than
// as a project, so this publishes them a coordinate to ask for. gradle/grug-core.gradle holds the
// repository, the version and the reason both are what they are, and the loaders read it too.
apply(from = "../gradle/grug-core.gradle")

val grugCoreVersion = the<ExtraPropertiesExtension>().get("grugCoreVersion") as String
val grugCoreRepository = the<ExtraPropertiesExtension>().get("grugCoreRepository") as File

version = grugCoreVersion

java.sourceCompatibility = JavaVersion.VERSION_17
java.targetCompatibility = JavaVersion.VERSION_17

// Core has to run on the Java 8 the 1.2.5 Forge loader boots with, so the main source is compiled
// to Java 8 bytecode even though the build JVM and the tests stay on 17. Java 8 classes run
// unchanged on the newer JVMs the other loaders use, so this constrains core rather than the matrix.
// `--release` also pins the API to Java 8, which `sourceCompatibility` alone would not.

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")
    implementation("org.jetbrains:annotations:23.0.0")
    implementation("com.google.guava:guava:33.7.2-jre")
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Handy locally: ./gradlew :core:jacocoTestReport. CI generates the badges from the combined
// per-runner report in .github/workflows/build.yml instead.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        csv.required.set(true)
    }
}

// The CI coverage job builds one report per runner from the uploaded exec/class artifacts using the
// JaCoCo CLI. Exposing the jar here keeps the CLI version in sync with the jacoco plugin's.
val jacocoCli: Configuration by configurations.creating {
    isTransitive = false
}
val jacocoToolVersion = the<JacocoPluginExtension>().toolVersion

dependencies {
    jacocoCli("org.jacoco:org.jacoco.cli:$jacocoToolVersion:nodeps")
}

tasks.register("printJacocoCliPath") {
    doLast {
        println("JACOCO_CLI=${jacocoCli.singleFile.absolutePath}")
    }
}

val generatedResourcesDir = layout.buildDirectory
    .dir("generated/resources")
    .get()
    .asFile

val generatedJavaDir = layout.buildDirectory
    .dir("generated/sources/grug")
    .get()
    .asFile

val nativesOutDir = generatedResourcesDir.resolve("natives")

sourceSets.main.get().resources.srcDir(generatedResourcesDir)
sourceSets.main.get().java.srcDir(generatedJavaDir)

fun runCommand(command: List<String>, workingDir: File? = null) {
    val process = ProcessBuilder(command)
        .apply {
            if (workingDir != null) {
                directory(workingDir)
            }
        }
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    println(output)

    if (exitCode != 0) {
        throw GradleException(
            "Command failed with exit code $exitCode: " +
                "${command.joinToString(" ")}\n$output"
        )
    }
}

val grugRsDir = layout.buildDirectory.dir("grug-rs").get().asFile
val grugRsUrl = "https://github.com/grug-lang/grug-rs.git"

// Pinned for stability. Bump this every so often.
val grugRsRevision = "92cce90489b9104b51786c01616c7817bd291bf7"

// Where the adapter links grug-rs from. cloneGrugRs and buildGrugRs both write this one file.
val libGruggers = grugRsDir.resolve("target/release/libgruggers.a")

// A libgruggers.a built elsewhere, as a path. See stagePrebuiltGrugRs below.
val prebuiltGrugRs = providers.gradleProperty("grug.prebuiltGrugRs").orNull

val cloneGrugRs = tasks.register("cloneGrugRs") {
    doLast {
        if (!grugRsDir.resolve(".git").exists()) {
            // init and fetch by name rather than clone --branch: a clone fetches the default branch,
            // and the pinned commit is not reachable from it.
            grugRsDir.mkdirs()
            runCommand(listOf("git", "init", grugRsDir.absolutePath), grugRsDir)
            runCommand(listOf("git", "remote", "add", "origin", grugRsUrl), grugRsDir)
        }

        runCommand(listOf("git", "fetch", "--depth", "1", "origin", grugRsRevision), grugRsDir)
        runCommand(listOf("git", "checkout", "--force", grugRsRevision), grugRsDir)
        runCommand(listOf("git", "reset", "--hard", grugRsRevision), grugRsDir)
    }
}

val buildGrugRs = tasks.register("buildGrugRs") {
    dependsOn(cloneGrugRs)

    outputs.file(libGruggers)
    outputs.upToDateWhen { false }

    doLast {
        runCommand(
            listOf("cargo", "build", "--release", "-p", "gruggers"),
            grugRsDir
        )
    }
}

// CI builds the grug-rs static library once and hands the same libgruggers.a to every job as an
// artifact, because it depends on nothing but the pinned revision above: the same bytes link into
// all five loaders. Pointing -Pgrug.prebuiltGrugRs at that file stages it where buildGrugAdapter
// links from and takes cloneGrugRs and buildGrugRs out of the task graph, so a job that was handed a
// library never clones grug-rs or runs cargo. Registered only when the property is set, which leaves
// a local build's task graph, and its up-to-date behaviour, exactly as it was.
val stagePrebuiltGrugRs = prebuiltGrugRs?.let { prebuilt ->
    tasks.register("stagePrebuiltGrugRs") {
        inputs.file(prebuilt)
        outputs.file(libGruggers)

        doLast {
            libGruggers.parentFile.mkdirs()
            File(prebuilt).copyTo(libGruggers, overwrite = true)
        }
    }
}

val nativeSrcDir = file("src/main/native")

val generateGrugAdapter = tasks.register("generateGrugAdapter") {
    val modApiJson = rootProject.file("mod_api.json")
    val generatorScript = file("generate.py")

    val generatedC = layout.buildDirectory
        .dir("generated/grug")
        .get()
        .asFile
        .resolve("adapter_generated.c")

    val generatedExportFnsJava = generatedJavaDir
        .resolve("net/grug/minecraft/grug/ExportFns.java")

    val generatedGenericFnsJava = generatedJavaDir
        .resolve("net/grug/minecraft/grug/GenericHostFunctions.java")

    inputs.file(modApiJson)
    inputs.file(generatorScript)

    outputs.file(generatedC)
    outputs.file(generatedExportFnsJava)
    outputs.file(generatedGenericFnsJava)

    doLast {
        generatedC.parentFile.mkdirs()
        generatedExportFnsJava.parentFile.mkdirs()
        generatedGenericFnsJava.parentFile.mkdirs()

        runCommand(
            listOf(
                "python3",
                generatorScript.absolutePath,
                modApiJson.absolutePath,
                generatedC.absolutePath,
                generatedExportFnsJava.absolutePath,
                generatedGenericFnsJava.absolutePath
            )
        )
    }
}

val buildGrugAdapter = tasks.register("buildGrugAdapter") {
    dependsOn(stagePrebuiltGrugRs ?: buildGrugRs, generateGrugAdapter)

    val adapterC = nativeSrcDir.resolve("adapter.c")

    val generatedC = generateGrugAdapter.get()
        .outputs
        .files
        .filter { it.name.endsWith(".c") }
        .singleFile

    val outLib = nativesOutDir
        .resolve("libadapter.so")

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
                "cc",
                "-shared",
                "-fPIC",
                "-I$javaHome/include",
                "-I$javaHome/include/linux",
                "-I${nativeSrcDir.absolutePath}",
                adapterC.absolutePath,
                generatedC.absolutePath,
                libGruggers.absolutePath,
                "-o",
                outLib.absolutePath,
                "-ldl",
                "-lpthread",
                "-lm"
            )
        )
    }
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateGrugAdapter)
    options.encoding = "UTF-8"
    options.release.set(8)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildGrugAdapter)
    
    from(rootProject.file("mods")) {
        into("mods")
    }
    from(rootProject.file("mod_api.json"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            artifactId = "grug-core"
        }
    }

    repositories {
        // Inside the checkout rather than in ~/.m2. The loaders that build on their own wrapper
        // resolve core from here, and a checkout is a directory of its own, so one worktree's core
        // is invisible to the loaders beside another. Published to the repository rather than to
        // mavenLocal, so no build on this machine writes a coordinate somebody else can overwrite.
        maven {
            name = "grugBuild"
            url = grugCoreRepository.toURI()
        }
    }
}
