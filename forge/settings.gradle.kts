pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://maven.minecraftforge.net/") { name = "Forge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "DangoMusic-forge"

// 共用代码（core + ui）在 ../common，作为组合构建引进来
includeBuild("../common")
