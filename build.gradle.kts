plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.oxbreeze"
version = "0.2.1"
repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get()) else webstorm("2026.3")
    }
    testImplementation(kotlin("test"))
}
kotlin { jvmToolchain(21) }
intellijPlatform {
    pluginConfiguration { ideaVersion { sinceBuild = "263" } }
    pluginVerification { ides { recommended() } }
}
tasks.test { useJUnitPlatform() }
