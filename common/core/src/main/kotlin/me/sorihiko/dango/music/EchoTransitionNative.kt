package me.sorihiko.dango.music

import me.sorihiko.dango.DangoLogger
import java.nio.file.Files
import java.security.MessageDigest

/** JNI boundary to EchoMusic's original analysis, decision and mixing modules. */
internal object EchoTransitionNative {
    val available: Boolean by lazy {
        try {
            val resource = "/native/windows-x64/dango_echo_transition.dll"
            if (!System.getProperty("os.name", "").startsWith("Windows", true) ||
                System.getProperty("os.arch", "") !in setOf("amd64", "x86_64")) return@lazy false
            val bytes = javaClass.getResourceAsStream(resource)?.use { it.readBytes() } ?: return@lazy false
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val dir = Files.createTempDirectory("dango-echo-$hash-")
            val library = dir.resolve("dango_echo_transition.dll")
            Files.write(library, bytes)
            library.toFile().deleteOnExit()
            dir.toFile().deleteOnExit()
            System.load(library.toAbsolutePath().toString())
            DangoLogger.info("EchoMusic transition engine loaded")
            true
        } catch (e: LinkageError) {
            DangoLogger.warn("EchoMusic transition engine unavailable: {}", e.javaClass.simpleName)
            false
        } catch (e: Exception) {
            DangoLogger.warn("EchoMusic transition engine unavailable: {}", e.javaClass.simpleName)
            false
        }
    }

    external fun analyze(mono: FloatArray, rate: Int, duration: Double, start: Double): String
    external fun decide(request: String): String
    external fun createMixer(plan: String, rate: Int, channels: Int, aGain: Float, bGain: Float): Long
    external fun push(handle: Long, deck: Int, pcm: FloatArray?)
    external fun render(handle: Long, frames: Int): FloatArray
    external fun info(handle: Long): Long
    external fun preroll(handle: Long, pcm: FloatArray)
    external fun remainder(handle: Long): FloatArray
    external fun release(handle: Long)
}
