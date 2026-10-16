// 所有 Minecraft 版本共用的代码（不依赖 Minecraft）。
// 不单独构建：由各平台层（legacy/、以后的 modern/）用 includeBuild("../common") 引进去。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // legacy/ 用 Gradle 7（只能用老的 Kotlin 插件），modern/ 用 Gradle 9（要新的）。
    // 必须和引用这里的那一边用同一个 Kotlin 版本
    val kotlinVersion = if (org.gradle.util.GradleVersion.current() >= org.gradle.util.GradleVersion.version("9.0")) "2.2.21" else "2.0.21"
    plugins {
        id("org.jetbrains.kotlin.jvm") version kotlinVersion
    }
}

rootProject.name = "dango-common"

include("core", "ui")
