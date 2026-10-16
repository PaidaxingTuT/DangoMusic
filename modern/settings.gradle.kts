pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "DangoMusic-modern"

// 共用代码（core + ui）在 ../common，作为组合构建引进来
includeBuild("../common")
