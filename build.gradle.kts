import java.util.UUID

plugins {
    id("net.minecraftforge.gradle") version "6.0.54"
    id("org.spongepowered.mixin") version "0.7.38"
}

group = "com.bettercontent"
version = property("mod_version") as String
base { archivesName.set("better-electrochemistry") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(17)); withSourcesJar() }

val modId = property("mod_id") as String
// Endpoint-only tests manually switch the load; batch-enabled tests must run in
// their own GameTest namespace once the recipe state mixins own that switch.
val nativeGameTestNamespace = providers.gradleProperty("nativeGameTestNamespace").getOrElse(modId)
require(nativeGameTestNamespace == modId || nativeGameTestNamespace == "${modId}_batch") {
    "Unsupported native GameTest namespace: $nativeGameTestNamespace"
}
val nativeFixture by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}
val nativeDirectory = layout.buildDirectory.dir("gametest/${UUID.randomUUID()}")
val upstreamMods = providers.environmentVariable("BC_UPSTREAM_MOD_DIR")
    .getOrElse(file("../../.cache/bc/packwiz-downloads/mods").absolutePath)

repositories {
    flatDir { dirs(upstreamMods) }
    mavenCentral()
    maven("https://maven.minecraftforge.net")
    maven("https://maven.createmod.net")
    maven("https://maven.ithundxr.dev/mirror")
    maven("https://maven.tterrag.com")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://www.cursemaven.com") { content { includeGroup("curse.maven") } }
}

minecraft {
    mappings("official", property("minecraft_version") as String)
    copyIdeResources = true
    runs {
        configureEach {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", file("build/createSrgToMcp/output.srg").absolutePath)
            mods { create(modId) { source(sourceSets.main.get()) } }
        }
        create("client")
        create("server") { arg("--nogui") }
        create("gameTestServer") {
            workingDirectory(nativeDirectory.get().asFile)
            property("forge.enableGameTest", "true")
            property("forge.gameTestServer", "true")
            property("forge.enabledGameTestNamespaces", nativeGameTestNamespace)
            mods { named(modId) { source(nativeFixture) } }
            arg("--nogui")
        }
    }
}

dependencies {
    minecraft("net.minecraftforge:forge:${property("minecraft_version")}-${property("forge_version")}")
    val create = "com.simibubi.create:create-1.20.1:6.0.8-289:slim"
    implementation(fg.deobf(create))
    implementation(fg.deobf("local:powergrid-mc1.20.1:0.5.4"))
    // PowerGrid requires Architectury; Create requires Ponder, Registrate and Flywheel.
    runtimeOnly(fg.deobf("local:architectury:9.2.14-forge"))
    compileOnly(fg.deobf("net.createmod.ponder:Ponder-Forge-1.20.1:1.0.92"))
    runtimeOnly(fg.deobf("net.createmod.ponder:Ponder-Forge-1.20.1:1.0.92"))
    compileOnly(fg.deobf("com.tterrag.registrate:Registrate:MC1.20-1.3.3"))
    runtimeOnly(fg.deobf("com.tterrag.registrate:Registrate:MC1.20-1.3.3"))
    runtimeOnly(fg.deobf("dev.engine-room.flywheel:flywheel-forge-1.20.1:1.0.5"))
    runtimeOnly("io.github.llamalad7:mixinextras-forge:0.5.4")
    compileOnly(fg.deobf("curse.maven:emi-580555:8081375"))
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<Test>().configureEach { useJUnitPlatform() }
tasks.processResources {
    val properties = listOf("mod_id", "mod_name", "mod_version").associateWith { project.property(it) }
    inputs.properties(properties)
    filesMatching("META-INF/mods.toml") { expand(properties) }
}
mixin {
    add(sourceSets.main.get(), "better_electrochemistry.refmap.json")
    config("better_electrochemistry.mixins.json")
}
tasks.named<Jar>("jar") {
    manifest.attributes("MixinConfigs" to "better_electrochemistry.mixins.json")
    finalizedBy("reobfJar")
}
tasks.register<Copy>("stageRuntimeJar") {
    dependsOn("reobfJar")
    from(layout.buildDirectory.file("reobfJar/output.jar"))
    into(layout.buildDirectory.dir("libs"))
    rename { "better-electrochemistry-${project.version}.jar" }
}
tasks.named("assemble") { dependsOn("stageRuntimeJar") }

// This fixture loads real native Create/PowerGrid dependencies. Its existence does not
// qualify optional Manufacturing/Airtight compatibility or full-pack gameplay.
tasks.matching { it.name == "runGameTestServer" }.configureEach {
    doFirst {
        val directory = nativeDirectory.get().asFile
        check(!directory.exists()) { "Refusing reused native fixture: $directory" }
        directory.mkdirs()
        directory.resolve("runtime-classpath.txt").writeText(
            configurations.runtimeClasspath.get().files.sortedBy { it.absolutePath }
                .joinToString("\n") { it.absolutePath })
        copy {
            from("src/nativeFixture/resources/data/better_electrochemistry/gametest/structures")
            into(directory.resolve("gameteststructures"))
        }
    }
}
tasks.register("verifyNative") {
    group = "verification"
    description = "Runs the isolated PowerGrid-loaded GameTests and requires the exact expected suite to finish."
    dependsOn("runGameTestServer")
    doLast {
        // ForgeGradle can exit 0 even when the forked Forge server fails during registry
        // startup, before discovering a single test. Its exit code is not a receipt.
        val expected = providers.gradleProperty("nativeGameTestCount").orNull?.toIntOrNull()
        check(expected != null && expected > 0) {
            "Supply the exact expected count, e.g. -PnativeGameTestCount=3; no implicit native PASS"
        }
        val log = nativeDirectory.get().asFile.resolve("logs/latest.log")
        check(log.isFile) { "No Forge GameTest server log at $log; zero tests observed" }
        val content = log.readText()
        check(content.contains("Enabled Gametest Namespaces: [$nativeGameTestNamespace]")) {
            "GameTest namespace $nativeGameTestNamespace was not enabled in $log"
        }
        val completed = Regex("=+\\s*(\\d+) GAME TESTS COMPLETE\\s*=+")
            .findAll(content).lastOrNull()?.groupValues?.get(1)?.toInt()
        val passed = Regex("All (\\d+) required tests passed")
            .findAll(content).lastOrNull()?.groupValues?.get(1)?.toInt()
        check(completed == expected && passed == expected && !content.contains("required tests failed")) {
            "Native GameTests did not pass: expected=$expected, completed=$completed, requiredPassed=$passed; see $log"
        }
        if (nativeGameTestNamespace == "${modId}_batch") {
            // Every current isolated batch test must load the same exact item-only
            // RecipeManager fixture. Update this gate when the test suite changes;
            // counting test discovery alone cannot establish a loaded recipe.
            val recipeReceipt = "native-batch-fixture stage=recipe-loaded " +
                "id=better_electrochemistry:fixture_cobble_to_stone " +
                "fingerprint=d3c05b7ee9fc910f3a9ed0fa128715c6303c375b832fd04d32501d2289198b6d " +
                "minVolts=10.0 workJoules=24.0"
            val loaded = Regex(Regex.escape(recipeReceipt)).findAll(content).count()
            check(loaded == expected) {
                "Expected $expected exact RecipeManager fixture receipts; found $loaded in $log"
            }
        }
        logger.lifecycle("Verified $expected native GameTests under $nativeGameTestNamespace; $log")
    }
}
