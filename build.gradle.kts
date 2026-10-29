plugins {
    id("net.fabricmc.fabric-loom") version "1.19.1"
    kotlin("jvm") version "2.4.21"
    id("com.diffplug.spotless") version "8.10.4"
}

version = property("mod_version") as String
group = property("maven_group") as String

base {
    archivesName = property("archives_base_name") as String
}

repositories {
    mavenCentral()
    // DevAuth is published by its author rather than to Maven Central.
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1") { name = "devauth" }
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create("mcnet") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    runs {
        named("client") {
            // -Pmcnet.account=<name> signs the dev client in through DevAuth
            // as that account. Without it the client starts as an offline
            // "PlayerNNN" and cannot join a hosted world. See the README.
            val account = providers.gradleProperty("mcnet.account").orNull
            if (account != null) {
                property("devauth.enabled", "true")
                property("devauth.account", account)
            }
        }
    }
}

// iroh's Kotlin bindings with the Mainline DHT patch from dht/ applied. There
// is no fork: dht/build.sh clones upstream iroh-ffi into a temporary
// directory, patches it, builds the native library and generates the bindings
// from it. Three ways to get them, as with the voice addon's video library:
//
//  1. -Pmcnet.iroh=<dir>: the bindings plus prebuilt libraries for every
//     platform, laid out as <dir>/kotlin and <dir>/natives/<jna prefix>. This
//     is what CI passes after its per-platform build jobs.
//  2. cargo on the PATH: run dht/build.sh here. The natives then cover this
//     machine only, so the jar runs on nothing else.
//  3. Neither: the published computer.iroh:iroh jar, which has natives for
//     every platform but no DHT, so MainlineDht falls back to n0's resolver.
//     -Pmcnet.dht=false forces this.
//
// Patched bindings are compiled straight into the mod instead of nested as a
// jar, because Jar-in-Jar needs a maven coordinate to generate metadata from.
val irohVersion = property("iroh_version") as String
val prebuiltIroh = providers.gradleProperty("mcnet.iroh").map { file(it) }
val cargoOnPath =
    System
        .getenv("PATH")
        .orEmpty()
        .split(File.pathSeparator)
        .any { File(it, "cargo").canExecute() || File(it, "cargo.exe").canExecute() }
val buildIrohLocally =
    !prebuiltIroh.isPresent && cargoOnPath && providers.gradleProperty("mcnet.dht").orNull != "false"
val patchedIroh = prebuiltIroh.isPresent || buildIrohLocally

// The voice addon compiles against the same bindings, so it needs to know
// which kind the core ended up with.
extra["patchedIroh"] = patchedIroh

val builtIroh = layout.buildDirectory.dir("iroh-ffi")
val irohDir = prebuiltIroh.orElse(builtIroh.map { it.asFile })

val buildIroh =
    tasks.register<Exec>("buildIroh") {
        description = "Clones iroh-ffi, applies the DHT patch and builds it for this machine."
        enabled = buildIrohLocally
        inputs.files(fileTree("dht") { exclude("target") }).withPropertyName("patch")
        inputs.property("irohVersion", irohVersion)
        outputs.dir(builtIroh)
        commandLine("bash", "dht/build.sh", builtIroh.get().asFile.absolutePath)
    }

val irohBindings =
    tasks.register<Sync>("irohBindings") {
        description = "Collects the patched iroh Kotlin bindings for compilation."
        dependsOn(buildIroh)
        from(irohDir.map { it.resolve("kotlin") })
        into(layout.buildDirectory.dir("generated/iroh/kotlin"))
    }

val irohNatives =
    tasks.register<Sync>("irohNatives") {
        description = "Puts the patched iroh libraries where JNA's classpath lookup finds them."
        dependsOn(buildIroh)
        from(irohDir.map { it.resolve("natives") })
        into(layout.buildDirectory.dir("generated/iroh/natives"))
    }

if (patchedIroh) {
    sourceSets.main {
        kotlin.srcDir(irohBindings)
        resources.srcDir(irohNatives)
    }
    if (!prebuiltIroh.isPresent) {
        logger.lifecycle("MCNet: building iroh with DHT support for this machine only (see dht/README.md)")
    }
}

// Dependencies that must travel inside the built jar, because they are not
// shipped by Minecraft, Fabric Loader or fabric-language-kotlin.
val bundled: Configuration = configurations.create("bundled")

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // Provides the `kotlin` language adapter used by fabric.mod.json, plus the
    // Kotlin stdlib and kotlinx-coroutines at runtime.
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // The published iroh bindings, only when the patched ones above are not
    // being built. Transitivity is switched off deliberately: the artifact
    // asks for its own kotlin-stdlib and kotlinx-coroutines, which
    // fabric-language-kotlin already puts on the runtime classpath at a newer
    // version. JNA is pulled in explicitly below instead, and the patched
    // bindings need it too.
    if (!patchedIroh) {
        bundled("computer.iroh:iroh:$irohVersion") { isTransitive = false }
    }
    bundled("net.java.dev.jna:jna:${property("jna_version")}")

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
    from("LICENSE") { rename { "${it}_${base.archivesName.get()}" } }
}

// ktlint lints and formats. Spotless hooks spotlessCheck into `check`, so a
// plain `./gradlew build` fails on unformatted code; `./gradlew spotlessApply`
// fixes it. Rule tweaks live in .editorconfig, where IDEs pick them up too.
val ktlintVersion = "1.8.0"

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
    }
}
