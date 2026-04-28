val group: String by project
val version: String by project
val repo: String by project

project.group = group
project.version = version

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc-repo" }
    maven("https://oss.sonatype.org/content/groups/public/") { name = "sonatype" }
    maven("https://repo.onarandombox.com/content/groups/public/")
    maven("https://jitpack.io") { name = "jitpack" }
    maven("https://maven.joutak.ru/snapshots")
}

dependencies {
    compileOnly(libs.kotlin)
    compileOnly(libs.paper)

    compileOnly("com.onarandombox.multiversecore:multiverse-core:4.3.14")
    implementation("ru.joutak:minigamesapi:3.4.3-128")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
}

kotlin {
    jvmToolchain(
        libs.versions.jdk
            .get()
            .toInt(),
    )
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    enabled = false
}

tasks.processResources {
    val minecraftVersion =
        libs.versions.paper
            .get()
            .substringBefore("-")

    // commitHash is optional (CI may pass it via -PcommitHash=...)
    val commitHash = (findProperty("commitHash") as String?)?.takeIf { it.isNotBlank() }

    val website =
        if (repo.isBlank()) {
            "https://joutak.ru"
        } else {
            if (commitHash.isNullOrBlank()) repo else "$repo/tree/$commitHash"
        }

    val props =
        mapOf(
            "NAME" to project.name,
            "VERSION" to project.version,
            "MINECRAFT_VERSION" to minecraftVersion,
            "KOTLIN_VERSION" to libs.versions.kotlin.get(),
            "WEBSITE" to website,
        )

    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveFileName.set("${project.name}-${project.version}.jar")

    if (System.getenv("TEST_PLUGIN_BUILD") != null) {
        val serverPath = System.getenv("SERVER_PATH")
        if (serverPath != null) {
            destinationDirectory.set(file("$serverPath\\plugins"))
        } else {
            logger.warn("SERVER_PATH property is not set!")
        }
    }
}
