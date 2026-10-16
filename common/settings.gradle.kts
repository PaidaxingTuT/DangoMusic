// 所有 Minecraft 版本共用的代码（不依赖 Minecraft）。
// 不单独构建：由各平台层（legacy/、以后的 modern/）用 includeBuild("../common") 引进去。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "dango-common"

include("core", "ui")
