import java.io.File

plugins {
    id("java-library")
}

java.sourceCompatibility = JavaVersion.VERSION_17
java.targetCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")
    implementation("org.jetbrains:annotations:23.0.0")
    implementation("com.google.guava:guava:33.2.1-jre")
}

// Define the generated resources and Java source directories inside the core build folder
val generatedResourcesDir = layout.buildDirectory.dir("generated/resources").get().asFile
val generatedJavaDir = layout.buildDirectory.dir("generated/sources/grug").get().asFile
val nativesOutDir = generatedResourcesDir.resolve("natives")

// Add the generated directories to the main source set
sourceSets.main.get().resources.srcDir(generatedResourcesDir)
sourceSets.main.get().java.srcDir(generatedJavaDir)

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
    outputs.upToDateWhen { false }
    doLast {
        runCommand(listOf("cargo", "build", "--release", "-p", "gruggers"), grugRsDir)
    }
}

val nativeSrcDir = file("src/main/native")

val generateGrugAdapter = tasks.register("generateGrugAdapter") {
    val modApiJson = file("src/main/resources/mod_api.json")
    val generatorScript = file("generate.py")
    val generatedC = layout.buildDirectory.dir("generated/grug").get().asFile.resolve("adapter_generated.c")
    val generatedExportFnsJava = generatedJavaDir.resolve("net/grug/minecraft/grug/ExportFns.java")
    val generatedGenericFnsJava = generatedJavaDir.resolve("net/grug/minecraft/grug/GenericGameFunctions.java")

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
    dependsOn(buildGrugRs, generateGrugAdapter)
    val adapterC = nativeSrcDir.resolve("adapter.c")
    val generatedC = generateGrugAdapter.get().outputs.files.filter { it.name.endsWith(".c") }.singleFile
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

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateGrugAdapter)
    options.encoding = "UTF-8"
}

tasks.named("processResources") {
    dependsOn(buildGrugAdapter)
}
