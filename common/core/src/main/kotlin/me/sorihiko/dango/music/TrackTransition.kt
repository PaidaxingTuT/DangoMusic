package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CancellationException
import kotlin.math.roundToLong

enum class TrackTransitionMode(val label: String, val nativeId: String) {
    OFF("Off", "none"), GAPLESS("Gapless playback", "gapless"), FADE("Crossfade", "fade"),
    NATURAL("Natural blend", "automix-basic"), RHYTHM("Rhythm blend", "automix-pro");

    val overlaps: Boolean get() = this != OFF && this != GAPLESS
}

internal object TrackTransition {
    class Plan(val json: String, val request: String) {
        private val data = JsonParser().parse(json).asJsonObject
        val exitMs = (data.get("a_cut_secs").asDouble * 1000).roundToLong()
        val entryMs = (data.get("b_start_secs").asDouble * 1000).roundToLong()
        val overlapMs = (data.get("overlap_secs").asDouble * 1000).roundToLong()
        val endMs = (data.get("a_end_secs").asDouble * 1000).roundToLong()
        val speed = data.get("a_tempo_ratio").asDouble
        val template: String? = data.get("template")?.takeUnless { it.isJsonNull }?.asString
        val bassExchange = template != null
        val mode = TrackTransitionMode.entries.first { it.nativeId == data.get("mode").asString }
        val note: String = data.get("note").asString
        fun withData(edit: (JsonObject) -> Unit): Plan {
            val copy = JsonParser().parse(json).asJsonObject
            edit(copy)
            return Plan(copy.toString(), request)
        }
    }

    fun prefetchMs(mode: TrackTransitionMode, seconds: Int): Long = when (mode) {
        TrackTransitionMode.OFF -> 0L
        TrackTransitionMode.GAPLESS -> 30_000L
        TrackTransitionMode.FADE -> 30_000L + seconds.coerceIn(0, 15) * 1000L
        else -> 75_000L
    }

    // Split frames at the planned cut in MusicPlayer. Only compensate for a genuinely late preparation.
    fun atPosition(plan: Plan, positionMs: Long, durationMs: Long): Plan? {
        if (positionMs < plan.exitMs) return null
        val late = positionMs - plan.exitMs
        if (late <= 1 || plan.overlapMs == 0L) return plan
        val end = minOf(plan.endMs, durationMs)
        val overlap = (end - positionMs) / plan.speed
        if (overlap <= 0) return null
        return plan.withData {
            it.addProperty("a_cut_secs", positionMs / 1000.0)
            it.addProperty("overlap_secs", overlap / 1000.0)
        }
    }

    fun manualAt(mode: TrackTransitionMode, planned: Plan?, positionMs: Long, durationMs: Long,
                 incomingMs: Long, seconds: Int): Plan? {
        if (planned == null || !EchoTransitionNative.available) return null
        val request = JsonParser().parse(planned.request).asJsonObject
        request.addProperty("mode", mode.nativeId)
        request.addProperty("manual", true)
        request.addProperty("position_secs", positionMs / 1000.0)
        request.addProperty("fade_secs", seconds.coerceIn(0, 15))
        val json = EchoTransitionNative.decide(request.toString())
        return json.takeIf { it.isNotEmpty() }?.let { Plan(it, planned.request) }
    }

    @JvmOverloads
    fun plan(mode: TrackTransitionMode, outgoing: ByteArray, incoming: ByteArray,
             outgoingMs: Long, incomingMs: Long, active: () -> Boolean, seconds: Int = 15,
             matchTempo: Boolean = false): Plan? {
        if (mode == TrackTransitionMode.OFF || !EchoTransitionNative.available) return null
        val end = AudioAnalysis.duration(outgoing, active).takeIf { it > 0 } ?: outgoingMs
        val nextEnd = AudioAnalysis.duration(incoming, active).takeIf { it > 0 } ?: incomingMs
        val musical = mode == TrackTransitionMode.NATURAL || mode == TrackTransitionMode.RHYTHM
        val tailWindow = if (musical) 90_000L else 12_000L
        val headWindow = if (musical) 45_000L else 6_000L
        val a = if (mode == TrackTransitionMode.FADE) metadata(end) else try {
            analyze(outgoing, end, (end - tailWindow).coerceAtLeast(0), tailWindow + 1000, active)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!active()) throw CancellationException()
            analyze(outgoing, end, (end - 12_000L).coerceAtLeast(0), 13_000L, active)
        }
        if (!active()) throw CancellationException()
        val b = if (mode == TrackTransitionMode.FADE) metadata(nextEnd)
            else analyze(incoming, nextEnd, 0L, headWindow, active)
        if (!active()) throw CancellationException()
        val request = JsonObject().apply {
            add("a", a)
            add("b", b)
            addProperty("mode", mode.nativeId)
            addProperty("fade_secs", seconds.coerceIn(0, 15))
            addProperty("match_tempo", matchTempo)
            addProperty("manual", false)
            addProperty("position_secs", 0.0)
        }.toString()
        val json = EchoTransitionNative.decide(request)
        return json.takeIf { it.isNotEmpty() }?.let { Plan(it, request) }
    }

    private fun metadata(durationMs: Long): JsonObject = JsonObject().apply {
        addProperty("duration_secs", durationMs / 1000.0)
        addProperty("window_start_secs", 0.0)
        addProperty("window_end_secs", 0.0)
        addProperty("beat_confidence", 0.0)
        add("energy", com.google.gson.JsonArray())
        add("sections", com.google.gson.JsonArray())
    }

    private fun analyze(bytes: ByteArray, durationMs: Long, startMs: Long, windowMs: Long,
                        active: () -> Boolean): JsonObject {
        val started = AudioDecoder.start(bytes, startMs, active)
        started.decoder.use { decoder ->
            val first = started.first ?: error("No audio for transition analysis")
            val rate = first.rate
            require(rate in 8000..192000 && first.channels in 1..2)
            val capacity = ((windowMs + 1000) * rate / 1000).toInt()
            val mono = FloatArray(capacity)
            var count = 0
            var pending: AudioDecoder.Frame? = first
            val wanted = (windowMs * rate / 1000).toInt()
            while (count < wanted && active()) {
                val frame = pending ?: decoder.read() ?: break
                pending = null
                check(frame.rate == rate && frame.channels in 1..2)
                // Match upstream's full final decoded chunk at the analysis window boundary.
                for (i in 0 until frame.length step frame.channels) {
                    if (count == capacity) break
                    var sum = 0f
                    for (channel in 0 until frame.channels) sum += frame.samples[i + channel] / 32768f
                    mono[count++] = sum / frame.channels
                }
            }
            if (!active()) throw CancellationException()
            check(count >= rate * 2) { "Not enough audio for transition analysis" }
            return JsonParser().parse(EchoTransitionNative.analyze(mono.copyOf(count), rate,
                durationMs / 1000.0, if (startMs == 0L) 0.0 else started.skippedMs / 1000.0)).asJsonObject
        }
    }
}
