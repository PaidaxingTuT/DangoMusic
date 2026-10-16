import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import java.util.Properties

plugins {
    id("dev.architectury.loom") version "1.10-SNAPSHOT"
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "8.3.6"
}

// 版本号统一写在根目录的 version.properties
val modVersion: String = Properties().apply {
    rootDir.resolve("../version.properties").inputStream().use { load(it) }
}.getProperty("version")
val mcVersion = property("minecraft_version") as String
val javaVersion = (property("java_version") as String).toInt()
val mcRange = property("minecraft_range") as String

group = "me.sorihiko.dango"
version = modVersion

base {
    archivesName.set("DangoMusic")
}

repositories {
    mavenCentral()
}

// 和 Fabric 版共用的高版本代码：画图、按键、界面（../modern/src），Fabric 的入口和 fabric.mod.json 除外
sourceSets["main"].resources.srcDir("../modern/src/main/resources")
kotlin {
    sourceSets["main"].kotlin.srcDir("../modern/src/main/kotlin")
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
    }
}

// 要打进 jar 的依赖：共用代码（common/core、common/ui）以及它们用到的 Kotlin 运行库、MP3 解码、二维码
val shade by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
configurations["implementation"].extendsFrom(shade)

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    forge("net.minecraftforge:forge:${property("forge_version")}")

    shade("me.sorihiko.dango:core:$modVersion")
    shade("me.sorihiko.dango:ui:$modVersion")
    shade(kotlin("stdlib"))
    shade("javazoom:jlayer:1.0.1")
    shade("com.google.zxing:core:3.3.3")
}

java {
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
    options.encoding = "UTF-8"
}

tasks.processResources {
    exclude("fabric.mod.json")
    inputs.property("version", modVersion)
    inputs.property("minecraft_range", mcRange)
    filesMatching("META-INF/mods.toml") {
        expand(mapOf("version" to modVersion, "minecraft_range" to mcRange))
    }
}

// 先把依赖打进去、改包名（避免和其它 mod 冲突），再交给 Loom 转成 Forge 运行时用的名字
tasks.shadowJar {
    configurations = listOf(shade)
    archiveClassifier.set("dev-shadow")
    relocate("kotlin", "me.sorihiko.dango.shadow.kotlin")
    relocate("org.intellij.lang.annotations", "me.sorihiko.dango.shadow.annotations.intellij")
    relocate("org.jetbrains.annotations", "me.sorihiko.dango.shadow.annotations.jetbrains")
    relocate("javazoom", "me.sorihiko.dango.shadow.javazoom")
    relocate("com.google.zxing", "me.sorihiko.dango.shadow.zxing")
    exclude("module-info.class", "META-INF/versions/**", "META-INF/*.kotlin_module")
}

tasks.remapJar {
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    archiveVersion.set("$modVersion+$mcVersion")
    archiveClassifier.set("forge")                   // DangoMusic-1.1.0+1.20.1-forge.jar
}
