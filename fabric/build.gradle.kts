plugins {
    id("fabric-loom")
}

val minecraftVersion = property("minecraftVersion") as String
val yarnMappings = property("yarnMappings") as String
val fabricLoaderVersion = property("fabricLoaderVersion") as String
val modId = property("modId") as String
val modVersion = property("modVersion") as String

version = modVersion
base.archivesName = "TwitchHUD-Fabric-$minecraftVersion"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

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
