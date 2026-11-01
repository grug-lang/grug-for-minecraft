import java.io.File
import java.util.concurrent.Callable
import org.gradle.jvm.tasks.Jar
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension

plugins {
    id("java-library")
    id("maven-publish")
    id("jacoco")
    id("com.gradleup.shadow") version "8.3.9"
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

// Compile-time stand-ins for the loaders' @Mod annotations, which GrugForgeMod carries, and for the
// two Mixin interfaces GrugMixinPlugin implements. Never packaged: the game brings the real ones.
// Mixin's own jar is kept off every class path, since it registers an annotation processor that
// javac would then discover in tests that compile code.
val stubs: SourceSet = sourceSets.create("stubs")

// The tool that records what each coverage test covered, for the carry-over of the tests a diff
// cannot affect (#786): one JVM over the per-test exec data, against the JaCoCo core library,
// rather than one jacoco-cli invocation per file. It is a build tool, not core: core's main
// sources compile with --release 8 and are packaged into the game, and this is neither. Its jar
// bundles the core library's classes and the asm classes the library calls, which the plain core
// jar does not hold, so the tool has no runtime dependency at all: the coverage job adds nothing
// to its classpath.
val coverageMapTool: SourceSet = sourceSets.create("coverageMapTool")

// The JaCoCo core library and the asm jars it resolves to, for the tool's jar alone: the jar
// bundles every jar in the set, and nothing else.
val coverageMapToolBundledJars: Configuration by configurations.creating

// The CI coverage job builds one report per runner from the uploaded exec/class artifacts using the
// JaCoCo CLI. Exposing the jar here keeps the CLI version in sync with the jacoco plugin's, and
// the coverage map tool compiles against the same core library version.
val jacocoToolVersion = the<JacocoPluginExtension>().toolVersion

dependencies {
    // The game provides ASM wherever GrugMixinPlugin is loaded, so it is not packaged either.
    "stubsCompileOnly"("org.ow2.asm:asm-tree:9.7.1")
    compileOnly(stubs.output)
    compileOnly("org.ow2.asm:asm-tree:9.7.1")
    testImplementation(stubs.output)
    testImplementation("org.ow2.asm:asm-tree:9.7.1")

    // The coverage map tool's own classes and the JaCoCo core library they link against, which
    // the tool's tests drive with class files they write themselves (#786).
    testImplementation(coverageMapTool.output)
    testImplementation("org.jacoco:org.jacoco.core:$jacocoToolVersion")

    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")
    implementation("org.jetbrains:annotations:26.1.0")
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// CI compiles from a fresh checkout, so incremental compilation has no previous output to be
// incremental against and still snapshots its whole compile classpath. A local build keeps it, where
// the previous output is still there. See #702.
tasks.withType<JavaCompile>().configureEach {
    if (System.getenv("GRUG_CI") == "true") {
        options.isIncremental = false
    }
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

dependencies {
    jacocoCli("org.jacoco:org.jacoco.cli:$jacocoToolVersion:nodeps")
}

tasks.register("printJacocoCliPath") {
    doLast {
        println("JACOCO_CLI=${jacocoCli.singleFile.absolutePath}")
    }
}

dependencies {
    coverageMapToolBundledJars("org.jacoco:org.jacoco.core:$jacocoToolVersion")
    "coverageMapToolImplementation"("org.jacoco:org.jacoco.core:$jacocoToolVersion")
}

tasks.register<Jar>("coverageMapToolJar") {
    archiveFileName.set("coverage-map-tool.jar")
    from(coverageMapTool.output)
    // The core library and the asm it calls, one tree per jar: a tool with no classpath to get
    // wrong in a workflow. The bundled jars' module descriptors and manifests would fight the
    // jar's own, so they stay out.
    for (jar in coverageMapToolBundledJars) {
        from(zipTree(Callable { jar as File })) {
            exclude("module-info.class")
            exclude("META-INF/**")
        }
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

// The native is built for the OS the build runs on: a Linux shared object with cc, or a Windows DLL
// with the MSVC compiler (cl), which the build finds on the PATH of a Developer Command Prompt. A
// release holds every native, each built on its own OS (#491).
val windows = System.getProperty("os.name").startsWith("Windows")

val grugRsDir = layout.buildDirectory.dir("grug-rs").get().asFile
val grugRsUrl = "https://github.com/grug-lang/grug-rs.git"

// Pinned for stability. Bump this every so often.
val grugRsRevision = "bfa5268d57e26f62e82befa01a60844cd7b4cbfc"

// Where the adapter links grug-rs from. cloneGrugRs and buildGrugRs both write this one file.
val libGruggers = grugRsDir.resolve(
    if (windows) "target/release/gruggers.lib" else "target/release/libgruggers.a"
)

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
                if (windows) "python" else "python3",
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
        .resolve(if (windows) "adapter.dll" else "libadapter.so")

    inputs.file(adapterC)
    inputs.file(generatedC)
    inputs.file(nativeSrcDir.resolve("adapter_shared.h"))
    inputs.file(libGruggers)

    outputs.file(outLib)

    doLast {
        nativesOutDir.mkdirs()

        val javaHome = System.getProperty("java.home")

        if (windows) {
            // /MD matches the dynamic C runtime Rust links by default, which every JVM has loaded
            // already. The objects go to a directory of their own, not into the build's working one.
            // The libraries after gruggers.lib are what a Rust static library asks its linker for
            // (rustc --print native-static-libs).
            val objects = nativesOutDir.resolve("obj")
            objects.mkdirs()
            runCommand(
                listOf(
                    "cl",
                    "/nologo",
                    "/MD",
                    "/LD",
                    "/I$javaHome\\include",
                    "/I$javaHome\\include\\win32",
                    "/I${nativeSrcDir.absolutePath}",
                    adapterC.absolutePath,
                    generatedC.absolutePath,
                    libGruggers.absolutePath,
                    "/Fo${objects.absolutePath}\\",
                    "/Fe${outLib.absolutePath}",
                    "/link",
                    "advapi32.lib",
                    "bcrypt.lib",
                    "kernel32.lib",
                    "ntdll.lib",
                    "userenv.lib",
                    "ws2_32.lib"
                )
            )
        } else {
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
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateGrugAdapter)
    options.encoding = "UTF-8"
    options.release.set(8)
}

// The list of bundled mod files GrugModsExtractor copies on a first run. It reads them by resource
// name rather than walking the jar, because a jar nested inside another one is not a directory it
// can walk on every loader.
val bundledModsIndexDir = layout.buildDirectory.dir("generated/bundled-mods-index")

// mods/coverage/ holds test fixtures, which nobody installs to play and which ask for more block ids
// than a pack has free (#509). The production and coexistence jobs install it beside the jar.
val unbundledMods = "coverage/**"

val generateBundledModsIndex = tasks.register("generateBundledModsIndex") {
    val modsDir = rootProject.file("mods")
    inputs.dir(modsDir)
    outputs.dir(bundledModsIndexDir)

    doLast {
        // fileTree applies Gradle's default excludes, as processResources does when it copies the
        // mods, so the index lists exactly the files the jar holds.
        val paths = fileTree(modsDir) { exclude(unbundledMods) }.files
            .map { it.relativeTo(modsDir).invariantSeparatorsPath }
            .sorted()
            .toList()
        val index = bundledModsIndexDir.get().file("grug_bundled_mods.txt").asFile
        index.parentFile.mkdirs()
        index.writeText(paths.joinToString("\n", postfix = "\n"))
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildGrugAdapter)

    from(rootProject.file("mods")) {
        exclude(unbundledMods)
        into("mods")
    }
    from(generateBundledModsIndex)
    from(rootProject.file("mod_api.json"))
}

// The core a player's game runs: gson inside it, relocated. Alpha, Beta and 1.2.5 ship no gson, so it
// has to come with grug, and it has to be relocated because 1.20.6 Forge already has gson as a module
// of its own, and a mod jar that holds the same package fails to load there. The development runs
// keep the plain jar, so JaCoCo still matches their coverage against core's compiled classes.
tasks.shadowJar {
    archiveClassifier.set("release")
    configurations = listOf(project.configurations.runtimeClasspath.get())
    dependencies {
        exclude(dependency("org.jetbrains:annotations"))
        exclude(dependency("com.google.errorprone:error_prone_annotations"))
    }
    relocate("com.google.gson", "net.grug.minecraft.shadow.gson")
    // A module descriptor would make 1.20.6 Forge load the jar as a named module of its own.
    exclude(
        "module-info.class",
        "META-INF/versions/*/module-info.class",
        "META-INF/maven/**",
        "META-INF/proguard/**",
    )
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            // The Shadow plugin adds the release jar to this component as a variant of its own.
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
