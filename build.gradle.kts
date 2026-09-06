import java.net.URI
import java.security.MessageDigest

plugins { java }

group = "gg.mira"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val miraCoreVersion = "0.5.2"
val miraCoreSha256 = "857611b2951a7a026ac7a9ec734e37f7764e33d84f05dec97a6736861d3af170"
val miraCoreJar = layout.projectDirectory.file("libs/MiraCore-$miraCoreVersion.jar").asFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

val downloadMiraCore by tasks.registering {
    doLast {
        if (!miraCoreJar.exists() || sha256(miraCoreJar) != miraCoreSha256) {
            miraCoreJar.parentFile.mkdirs()
            URI("https://github.com/FiveSOCE/Mira-core/releases/download/v$miraCoreVersion/MiraCore-$miraCoreVersion.jar")
                .toURL().openStream().use { input ->
                    miraCoreJar.outputStream().use { output -> input.copyTo(output) }
                }
        }
        check(sha256(miraCoreJar) == miraCoreSha256) { "MiraCore dependency failed SHA-256 verification" }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files(miraCoreJar))
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.withType<JavaCompile>().configureEach {
    dependsOn(downloadMiraCore)
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar { archiveFileName.set("MiraChatItems-${project.version}.jar") }

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
