// 核心：事件、模块、设置、网易云接口、播放器、一起听歌。不碰任何绘图。
dependencies {
    "implementation"("org.jcraft:jorbis:0.0.17")     // Ogg Vorbis 解码
    "implementation"("javazoom:jlayer:1.0.1")       // MP3 解码
    "implementation"("com.google.zxing:core:3.3.3") // 登录二维码
}

// EchoMusic's analysis, decisions and mixer are bundled as one JNI library.
val echoWindowsX64 = System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
    System.getProperty("os.arch") in setOf("amd64", "x86_64")
val echoCargo = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
    .map { File(it.trim('"'), "cargo.exe") }.firstOrNull { it.isFile }
if (echoWindowsX64 && echoCargo != null) {
    val source = rootProject.file("../native/echo-transition")
    val target = layout.buildDirectory.dir("echo-transition")
    val binary = target.map { it.file("x86_64-pc-windows-msvc/release/dango_echo_transition.dll") }
    val buildEchoTransition = tasks.register<Exec>("buildEchoTransition") {
        workingDir(source)
        commandLine(echoCargo.absolutePath, "build", "--release", "--locked", "--target", "x86_64-pc-windows-msvc",
            "--target-dir", target.get().asFile.absolutePath)
        inputs.files(source.resolve("Cargo.toml"), source.resolve("Cargo.lock"), source.resolve(".cargo/config.toml"))
        inputs.dir(source.resolve("src"))
        inputs.dir(source.resolve("vendor"))
        outputs.file(binary)
    }
    tasks.named<ProcessResources>("processResources") {
        dependsOn(buildEchoTransition)
        from(binary) { into("native/windows-x64") }
        from(source.resolve("README.md")) { into("META-INF/licenses/echo-transition") }
        from(source.resolve("LICENSE")) { into("META-INF/licenses/echo-transition") }
        from(source.resolve("vendor/soundtouch-rs/ECHOMUSIC_VENDOR.md")) { into("META-INF/licenses/echo-transition") }
        from(source.resolve("vendor/soundtouch-rs/LGPL-2.1.txt")) { into("META-INF/licenses/echo-transition") }
        from(source.resolve("vendor/soundtouch-rs/src/spectral/LICENSE-SIGNALSMITH.md")) { into("META-INF/licenses/echo-transition") }
    }
}
