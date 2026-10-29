plugins {
    // Versions come from the root build script; Gradle allows a plugin
    // version to be declared only once per build.
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
    id("com.diffplug.spotless")
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName = "mcnet-voice"
}

repositories {
    mavenCentral()
    // opus4j is published by its author rather than to Maven Central.
    maven("https://maven.maxhenkel.de/repository/public") { name = "henkelmax" }
    // DevAuth is published by its author rather than to Maven Central.
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1") { name = "devauth" }
}

val coreSourceSets = project(":").extensions.getByType<SourceSetContainer>()

loom {
    splitEnvironmentSourceSets()

    mods {
        create("mcnet-voice") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
        // Listing the core's source sets here is what lets `:voice:runClient`
        // see MCNet as a mod rather than as loose classes on the classpath.
        create("mcnet") {
            sourceSet(coreSourceSets["main"])
            sourceSet(coreSourceSets["client"])
        }
    }

    runs {
        named("client") {
            // Same -Pmcnet.account=<name> sign-in as the core's dev client.
            val account = providers.gradleProperty("mcnet.account").orNull
            if (account != null) {
                property("devauth.enabled", "true")
                property("devauth.account", account)
            }
        }
    }
}

// Dependencies nested into the addon jar. The iroh bindings and JNA are
// deliberately not here: the core mod already ships them, and Fabric loads
// every mod on one classpath, so a second copy would only add 29 MB.
val bundled: Configuration = configurations.create("bundled")

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // The core mod's compiled classes, straight from its source sets. Not the
    // core jar: that has iroh and JNA nested inside, and the loader would try
    // to load those nested jars a second time in dev. (Minecraft 26.x is
    // unobfuscated, so Loom no longer has a separate "named" configuration.)
    implementation(coreSourceSets["main"].output)
    "clientImplementation"(coreSourceSets["client"].output)

    // Compile-time and dev-run access to the iroh bindings the core bundles.
    // Same local-jar override as the core, so DHT builds work here too.
    val localIroh = providers.gradleProperty("mcnet.irohJar").orNull
    if (localIroh != null) {
        implementation(files(localIroh))
    } else {
        implementation("computer.iroh:iroh:${property("iroh_version")}") { isTransitive = false }
    }
    implementation("net.java.dev.jna:jna:${property("jna_version")}")

    // Opus. Its only declared dependency is a native loader that the published
    // jar already shades in, so transitivity is off to keep the nested set to
    // one jar.
    bundled("de.maxhenkel.opus4j:opus4j:${property("opus4j_version")}") { isTransitive = false }

    bundled.dependencies.forEach {
        implementation(it)
        include(it)
    }

    // Dev client sign-in. Runtime only, so it is never compiled against or
    // nested into the jar.
    runtimeOnly("me.djtheredstoner:DevAuth-fabric:${property("devauth_version")}")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}

// The native video library: webcam capture plus the AV1 encoder and decoder,
// a Rust crate under video/ that the voice jar bundles the way the core
// bundles iroh's natives. Three ways to get it, in order of preference:
//
//  1. -Pmcnet.videoNatives=<dir>: prebuilt libraries for every platform, laid
//     out as <dir>/<jna prefix>/<library>. This is what CI passes after its
//     per-platform build jobs.
//  2. cargo on the PATH: build it here, for this machine only. Needs cmake,
//     meson and ninja; see .mise.toml and the README.
//  3. Neither: the jar ships without it, voice works, video reports itself
//     unavailable. -Pmcnet.video=false forces this.
//
// The Kotlin bindings are generated from the built library and committed
// under src/client/generated, so compiling never needs cargo. They carry a
// checksum of the API they were generated from, and refuse to load a library
// that does not match, so a stale commit fails loudly rather than oddly.
val videoCrate = rootProject.layout.projectDirectory.dir("video")
val hostArch =
    when (val raw = System.getProperty("os.arch")) {
        "aarch64", "arm64" -> "aarch64"
        "amd64", "x86_64" -> "x86-64"
        else -> raw
    }
val hostOs = System.getProperty("os.name").lowercase()
val (hostJnaPrefix, hostVideoLibrary) =
    when {
        hostOs.contains("mac") -> "darwin-$hostArch" to "libmcnet_video.dylib"
        hostOs.contains("win") -> "win32-$hostArch" to "mcnet_video.dll"
        else -> "linux-$hostArch" to "libmcnet_video.so"
    }
val builtVideoLibrary = videoCrate.file("target/release/$hostVideoLibrary")
val prebuiltVideoNatives = providers.gradleProperty("mcnet.videoNatives").map { rootProject.file(it) }
val cargoOnPath =
    System
        .getenv("PATH")
        .orEmpty()
        .split(File.pathSeparator)
        .any { File(it, "cargo").canExecute() || File(it, "cargo.exe").canExecute() }
val buildVideoLocally =
    !prebuiltVideoNatives.isPresent && cargoOnPath && providers.gradleProperty("mcnet.video").orNull != "false"

val cargoBuild =
    tasks.register<Exec>("cargoBuild") {
        description = "Builds the native video library for this machine."
        enabled = buildVideoLocally
        workingDir = videoCrate.asFile
        commandLine("cargo", "build", "--release")
        outputs.file(builtVideoLibrary)
        // Always hand over to cargo, which tracks its own inputs, including
        // the vendored codec submodules moving to a new tag. A no-op build
        // takes well under a second.
        outputs.upToDateWhen { false }
    }

tasks.register<Exec>("generateVideoBindings") {
    description = "Regenerates the committed Kotlin bindings from the built video library."
    dependsOn(cargoBuild)
    workingDir = videoCrate.asFile
    val outDir = layout.projectDirectory.dir("src/client/generated/kotlin")
    doFirst { delete(outDir) }
    commandLine(
        "cargo",
        "run",
        "--release",
        "--quiet",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--library",
        builtVideoLibrary.asFile.absolutePath,
        "--language",
        "kotlin",
        "--no-format",
        "--out-dir",
        outDir.asFile.absolutePath,
    )
}

val bundleVideoNatives =
    tasks.register<Sync>("bundleVideoNatives") {
        description = "Puts the native video libraries where JNA's classpath lookup finds them."
        into(layout.buildDirectory.dir("generated/video-natives"))
        when {
            prebuiltVideoNatives.isPresent -> {
                from(prebuiltVideoNatives)
            }

            buildVideoLocally -> {
                dependsOn(cargoBuild)
                from(builtVideoLibrary) { into(hostJnaPrefix) }
            }

            else -> {
                doFirst { logger.warn("mcnet-voice: no native video library; the jar will not support cameras") }
            }
        }
    }

sourceSets.named("client") {
    kotlin.srcDir("src/client/generated/kotlin")
    resources.srcDir(bundleVideoNatives)
}

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "loader_version" to loaderVersion,
        )
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

tasks.jar {
    from(rootProject.file("LICENSE")) { rename { "${it}_${base.archivesName.get()}" } }
}

val ktlintVersion = "1.8.0"

spotless {
    kotlin {
        target("src/**/*.kt")
        // Machine-written by UniFFI; formatting it would only make
        // regeneration noisy.
        targetExclude("src/client/generated/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
    }
}
