import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

import java.util.Properties

plugins {
    kotlin("jvm") version "2.0.21" apply false
}

val modVersion: String = Properties().apply {
    rootDir.resolve("../version.properties").inputStream().use { load(it) }
}.getProperty("version")

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "me.sorihiko.dango"
    version = modVersion

    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net")
    }

    // 编译成 Java 8 字节码：1.8.9 跑在 Java 8 上，高版本的 Java 也能直接用
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(8)
    }

    dependencies {
        // 游戏本身带了这两个库（1.8.9 起每个版本都有），不打进 jar
        "compileOnly"("com.google.code.gson:gson:2.2.4")
        "compileOnly"("org.apache.logging.log4j:log4j-api:2.0-beta9")
    }
}
