package me.sorihiko.dango.music

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt

enum class TrackTransitionMode { OFF, GAPLESS, FADE, NATURAL, RHYTHM }

internal object TrackTransition {
    data class Plan(val exitMs: Long, val entryMs: Long, val overlapMs: Long, val bassExchange: Boolean = false)

    private const val HOP_MS = 10
    private const val ANALYSIS_MS = 12_000L
    private const val FADE_MS = 5_000L

    fun plan(mode: TrackTransitionMode, outgoing: ByteArray, incoming: ByteArray,
             outgoingMs: Long, incomingMs: Long, active: () -> Boolean): Plan? {
        if (mode == TrackTransitionMode.OFF || mode == TrackTransitionMode.GAPLESS) return null
        val end = AudioAnalysis.duration(outgoing, active).takeIf { it > 0 } ?: outgoingMs
        val nextDuration = AudioAnalysis.duration(incoming, active).takeIf { it > 0 } ?: incomingMs
        val overlap = minOf(FADE_MS, end / 3, nextDuration / 3)
        if (overlap < 500) return null
        val fade = Plan(end - overlap, 0, overlap)
        if (mode == TrackTransitionMode.FADE) return fade

        val tail = envelope(outgoing, (end - ANALYSIS_MS).coerceAtLeast(0), active)
        val head = envelope(incoming, 0, active)
        if (head.rms.isEmpty() || tail.rms.isEmpty()) return fade
        val entry = head.rms.indexOfFirst { it > head.silence }.takeIf { it >= 0 }
            ?.let { head.time(it).coerceAtMost(3_000) } ?: 0L
        val audibleEnd = tail.rms.indexOfLast { it > tail.silence }.takeIf { it >= 0 }
            ?.let { (tail.time(it) + HOP_MS).coerceIn((end - 2_000).coerceAtLeast(0), end) } ?: end

        if (mode == TrackTransitionMode.RHYTHM) {
            val a = tail.beatGrid()
            val b = head.beatGrid()
            if (a != null && b != null && abs(a.periodMs - b.periodMs) <= HOP_MS) {
                val bars = a.periodMs * 8L
                val exit = a.atOrBefore(audibleEnd - bars)
                val cue = b.atOrBefore(entry + b.periodMs - 1).coerceAtLeast(0)
                if (exit >= tail.startMs && exit >= 1_000 && cue + bars < nextDuration) {
                    return Plan(exit, cue, bars, bassExchange = true)
                }
            }
        }

        // Find a quiet entry into the outgoing fade without dropping more than two seconds of tail.
        val target = audibleEnd - overlap
        val radius = minOf(750L, overlap / 4)
        val candidates = tail.rms.indices.filter { abs(tail.time(it) - target) <= radius && tail.time(it) >= 0 }
        val exit = candidates.minByOrNull { tail.rms[it] }?.let(tail::time) ?: target
        val naturalOverlap = minOf(audibleEnd - exit, (nextDuration - entry) / 2)
        return if (exit >= 0 && naturalOverlap >= 500) Plan(exit, entry, naturalOverlap) else fade
    }

    private class Envelope(val startMs: Long, val rms: List<Float>) {
        val silence = maxOf(0.001f, (rms.maxOrNull() ?: 0f) * 0.025f)
        fun time(index: Int) = startMs + index * HOP_MS

        fun beatGrid(): BeatGrid? {
            val onset = FloatArray(rms.size) { i -> (rms[i] - rms[(i - 2).coerceAtLeast(0)]).coerceAtLeast(0f) }
            if (onset.count { it > silence } < 8) return null
            var best = 0.35
            var period = 0
            for (lag in 33..100) {
                var product = 0.0
                var a = 0.0
                var b = 0.0
                for (i in lag until onset.size) {
                    val x = onset[i].toDouble()
                    val y = onset[i - lag].toDouble()
                    product += x * y
                    a += x * x
                    b += y * y
                }
                val score = product / sqrt(a * b)
                if (score > best + 0.025) {
                    best = score
                    period = lag
                }
            }
            if (period == 0) return null
            val phase = FloatArray(period)
            for (i in onset.indices) phase[i % period] += onset[i]
            val peak = phase.indices.maxByOrNull { phase[it] } ?: return null
            return BeatGrid(time(peak), period * HOP_MS.toLong())
        }
    }

    private class BeatGrid(val originMs: Long, val periodMs: Long) {
        fun atOrBefore(ms: Long) = ms - Math.floorMod(ms - originMs, periodMs)
    }

    private fun envelope(bytes: ByteArray, startMs: Long, active: () -> Boolean): Envelope =
        AudioDecoder.open(bytes).use { decoder ->
            val start = decoder.skipTo(startMs, active).toLong()
            val rms = ArrayList<Float>()
            var energy = 0.0
            var frames = 0
            while (rms.size < ANALYSIS_MS / HOP_MS) {
                if (!active()) throw CancellationException()
                val frame = decoder.read() ?: break
                val blockFrames = (frame.rate * HOP_MS / 1000).coerceAtLeast(1)
                for (i in 0 until frame.length step frame.channels) {
                    for (channel in 0 until frame.channels) {
                        val sample = frame.samples[i + channel] / 32768.0
                        energy += sample * sample
                    }
                    if (++frames == blockFrames) {
                        rms.add(sqrt(energy / (frames * frame.channels)).toFloat())
                        if (rms.size >= ANALYSIS_MS / HOP_MS) break
                        frames = 0
                        energy = 0.0
                    }
                }
            }
            Envelope(start, rms)
        }
}
