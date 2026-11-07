package me.sorihiko.dango.music

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt

enum class TrackTransitionMode(val label: String) {
    OFF("Off"), GAPLESS("Gapless playback"), FADE("Crossfade"), NATURAL("Natural blend"), RHYTHM("Rhythm blend");

    val overlaps: Boolean get() = this != OFF && this != GAPLESS
}

internal object TrackTransition {
    data class Plan(val exitMs: Long, val entryMs: Long, val overlapMs: Long,
                    val bassExchange: Boolean = false, val speed: Double = 1.0, val beatMs: Long = 0L,
                    val mode: TrackTransitionMode = TrackTransitionMode.FADE)

    private const val HOP_MS = 10
    private const val ANALYSIS_MS = 12_000L
    /** A seek or a slow download can miss the musical cue. Use the remaining tail for a shorter fade. */
    fun atPosition(plan: Plan, positionMs: Long, durationMs: Long): Plan? {
        if (positionMs < plan.exitMs) return null
        val lateMs = positionMs - plan.exitMs
        val aligned = plan.bassExchange && lateMs <= 250
        val elapsed = if (aligned) (lateMs / plan.speed).toLong() else lateMs
        val remaining = plan.overlapMs - elapsed
        if (remaining >= 180) {
            val available = if (aligned || durationMs <= 0) remaining else minOf(remaining, durationMs - positionMs)
            if (available >= 180) return Plan(positionMs, plan.entryMs + if (aligned) elapsed else 0,
                available, aligned, if (aligned) plan.speed else 1.0,
                mode = if (!aligned && plan.mode == TrackTransitionMode.RHYTHM) TrackTransitionMode.NATURAL else plan.mode)
        }
        val tail = (durationMs - positionMs).coerceAtMost(plan.overlapMs)
        return if (tail >= 180) Plan(positionMs, 0, tail) else null
    }

    fun manualAt(mode: TrackTransitionMode, planned: Plan?, positionMs: Long, durationMs: Long,
                 incomingMs: Long, seconds: Int): Plan? {
        if (!mode.overlaps) return null
        val remaining = if (durationMs > 0) durationMs - positionMs else 3_500L
        val limit = minOf(seconds * 1000L, 3_500L, remaining)
        if (limit < 180) return null
        val cue = if (mode == TrackTransitionMode.FADE) 0L else planned?.entryMs ?: 0L
        if (planned?.bassExchange == true && planned.beatMs > 0 && limit >= 1_500) {
            val late = Math.floorMod(positionMs - planned.exitMs, planned.beatMs)
            if (late > 110) return null
            val entry = cue + (late / planned.speed).toLong()
            val beats = (planned.beatMs * 4 / planned.speed).toLong()
            val overlap = minOf(beats, limit, (remaining / planned.speed).toLong())
            if (entry + overlap < incomingMs) return Plan(positionMs, entry, overlap, true, planned.speed, mode = TrackTransitionMode.RHYTHM)
        }
        val overlap = if (incomingMs > 0) minOf(limit, incomingMs - cue) else limit
        return if (overlap >= 180) Plan(positionMs, cue, overlap,
            mode = if (mode != TrackTransitionMode.FADE && planned != null) TrackTransitionMode.NATURAL else TrackTransitionMode.FADE) else null
    }

    @JvmOverloads
    fun plan(mode: TrackTransitionMode, outgoing: ByteArray, incoming: ByteArray,
             outgoingMs: Long, incomingMs: Long, active: () -> Boolean, seconds: Int = 7, matchTempo: Boolean = true): Plan? {
        if (!mode.overlaps) return null
        val end = AudioAnalysis.duration(outgoing, active).takeIf { it > 0 } ?: outgoingMs
        val nextDuration = AudioAnalysis.duration(incoming, active).takeIf { it > 0 } ?: incomingMs
        val overlap = minOf(seconds * 1000L, end / 3, nextDuration / 3)
        if (overlap < 500) return null
        val fade = Plan(end - overlap, 0, overlap)
        if (mode == TrackTransitionMode.FADE) return fade

        val tail: Envelope
        val head: Envelope
        try {
            tail = envelope(outgoing, (end - ANALYSIS_MS).coerceAtLeast(0), active)
            head = envelope(incoming, 0, active)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return fade
        }
        if (head.rms.isEmpty() || tail.rms.isEmpty()) return fade
        val entry = head.rms.indexOfFirst { it > head.silence }.takeIf { it >= 0 }
            ?.let { head.time(it).coerceAtMost(3_000) } ?: 0L
        val audibleEnd = tail.rms.indexOfLast { it > tail.silence }.takeIf { it >= 0 }
            ?.let { (tail.time(it) + HOP_MS).coerceIn((end - 2_000).coerceAtLeast(0), end) } ?: end

        if (mode == TrackTransitionMode.RHYTHM) {
            val a = tail.beatGrid()
            val b = head.beatGrid()
            if (a != null && b != null) {
                val ratio = a.periodMs.toDouble() / b.periodMs
                val speed = when {
                    abs(a.periodMs - b.periodMs) <= HOP_MS -> 1.0
                    matchTempo && ratio in 0.92..1.08 -> ratio
                    else -> 0.0
                }
                val beats = if (8 * b.periodMs <= seconds * 1000L) 8L else 4L
                val bars = b.periodMs * beats
                val exit = a.atOrBefore(audibleEnd - a.periodMs * beats)
                val cue = b.atOrBefore(entry + b.periodMs - 1).coerceAtLeast(0)
                if (speed > 0 && exit >= tail.startMs && exit >= 1_000 && cue + bars < nextDuration) {
                    return Plan(exit, cue, bars, bassExchange = true, speed = speed, beatMs = a.periodMs, mode = TrackTransitionMode.RHYTHM)
                }
            }
        }

        // Find a quiet entry into the outgoing fade without dropping more than two seconds of tail.
        val target = audibleEnd - overlap
        val radius = minOf(750L, overlap / 4)
        val candidates = tail.rms.indices.filter { abs(tail.time(it) - target) <= radius && tail.time(it) >= 0 }
        val exit = candidates.minByOrNull { tail.levelAround(it) }?.let(tail::time) ?: target
        val naturalOverlap = minOf(audibleEnd - exit, (nextDuration - entry) / 2)
        return if (exit >= 0 && naturalOverlap >= 500) Plan(exit, entry, naturalOverlap, mode = TrackTransitionMode.NATURAL) else fade
    }

    private class Envelope(val startMs: Long, val rms: List<Float>) {
        val silence = maxOf(0.001f, (rms.maxOrNull() ?: 0f) * 0.025f)
        fun time(index: Int) = startMs + index * HOP_MS
        fun levelAround(index: Int): Double {
            val left = (index - 12).coerceAtLeast(0)
            val right = (index + 12).coerceAtMost(rms.lastIndex)
            var sum = 0.0
            for (i in left..right) sum += rms[i] * rms[i]
            return sum / (right - left + 1)
        }

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
