plugins {
    id("net.neoforged.moddev")
}

val minecraftVersion = property("minecraftVersion") as String
val neoforgeVersion = property("neoforgeVersion") as String
val modId = property("modId") as String
val modVersion = property("modVersion") as String

version = modVersion
base.archivesName = "TwitchHUD-NeoForge-$minecraftVersion"

neoForge {
    version = neoforgeVersion

    runs {
        create("client") {
            client()
        }
    }

    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    implementation(project(":common"))
}

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(mapOf("version" to modVersion, "modId" to modId))
    }
}

tasks.jar {
    from(project(":common").sourceSets.main.get().output)
}
