plugins {
    id("net.minecraftforge.gradle")
}

val minecraftVersion = property("minecraftVersion") as String
val forgeVersion = property("forgeVersion") as String
val modId = property("modId") as String
val modVersion = property("modVersion") as String

version = modVersion
base.archivesName = "TwitchHUD-Forge-$minecraftVersion"

minecraft {
    mappings("official", minecraftVersion)

    runs {
        register("client")
    }
}

repositories {
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
    mavenCentral()
}

dependencies {
    implementation(minecraft.dependency("net.minecraftforge:forge:$forgeVersion"))
    implementation(project(":common"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("META-INF/mods.toml") {
        expand(mapOf("version" to modVersion, "modId" to modId))
    }
}

tasks.jar {
    from(project(":common").sourceSets.main.get().output)
}
