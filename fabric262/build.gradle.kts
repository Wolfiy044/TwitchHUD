plugins {
    id("net.fabricmc.fabric-loom")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val minecraftVersion = property("minecraftVersion262") as String
val fabricLoaderVersion = property("fabricLoaderVersion") as String
val modId = property("modId") as String
val modVersion = property("modVersion") as String

version = modVersion
base.archivesName = "TwitchHUD-Fabric-$minecraftVersion"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    implementation(project(":common"))
}

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to modVersion, "modId" to modId))
    }
}

tasks.jar {
    from(project(":common").sourceSets.main.get().output)
}
