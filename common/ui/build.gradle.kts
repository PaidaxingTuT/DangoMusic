// 共用界面：播放器窗口、歌词、Song card、灵动岛。只通过 Gfx / Platform 接口画东西。
dependencies {
    "api"(project(":core"))
    "testImplementation"(kotlin("test-junit"))
    "testImplementation"("com.google.code.gson:gson:2.2.4")
    "testRuntimeOnly"("org.apache.logging.log4j:log4j-api:2.0-beta9")
}
