val modGroup = property("modGroup") as String
val modVersion = property("modVersion") as String

subprojects {
    apply(plugin = "java")

    group = modGroup
    version = modVersion

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}
