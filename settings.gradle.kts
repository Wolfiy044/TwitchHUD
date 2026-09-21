pluginManagement {
    val loomVersion = providers.gradleProperty("loomVersion").get()
    val forgeGradleVersion = providers.gradleProperty("forgegradleVersion").get()
    val neoForgeModDevVersion = providers.gradleProperty("neoforgeModdevVersion").get()

    plugins {
        id("fabric-loom") version loomVersion
        id("net.fabricmc.fabric-loom") version loomVersion
        id("net.minecraftforge.gradle") version forgeGradleVersion
        id("net.neoforged.moddev") version neoForgeModDevVersion
    }

    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.quiltmc.org/repository/release/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "twitchhud"

include("common")
include("fabric")
include("fabric1211")
include("fabric262")
include("forge")
include("neoforge")
