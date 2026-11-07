package me.sorihiko.dango.music

import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Feeds one PCM stream to the existing output while two compatible decoders overlap. */
internal class TrackBlend @JvmOverloads constructor(
    outgoing: AudioDecoder,
    outgoingHead: AudioDecoder.Frame,
    private val incoming: AudioDecoder,
    incomingHead: AudioDecoder.Frame,
    overlapMs: Long,
    private val outgoingGain: Float,
    private val incomingGain: Float,
    private val bassExchange: Boolean,
    speed: Double = 1.0,
) : AudioDecoder {
    private val rate = incomingHead.rate
    private val channels = incomingHead.channels
    private val overlapFrames = (overlapMs * rate / 1000).coerceAtLeast(1)
    private var endFrame = overlapFrames
    private var mixedFrames = 0L
    private val outgoing = if (speed == 1.0) outgoing else TempoStretch(outgoing, outgoingHead, speed)
    private var a: AudioDecoder.Frame? = outgoingHead.takeIf { speed == 1.0 }
    private var aOffset = 0
    private var aEndedAt = -1L
    private var b: AudioDecoder.Frame? = incomingHead
    private val lowPass = (1.0 - exp(-2.0 * Math.PI * 180 / rate)).toFloat()
    private val lowA = FloatArray(channels)
    private val lowB = FloatArray(channels)

    override fun read(): AudioDecoder.Frame? {
        val frame = b ?: incoming.read() ?: return null
        b = null
        check(frame.rate == rate && frame.channels == channels) { "Incoming audio format changed during transition" }
        val mixed = ShortArray(frame.length)
        val sum = FloatArray(channels)
        for (i in mixed.indices step channels) {
            if (mixedFrames < endFrame && aEndedAt < 0 && (a == null || aOffset == a?.length)) {
                a = outgoing.read()?.takeIf { it.rate == rate && it.channels == channels }
                aOffset = 0
                if (a == null) {
                    aEndedAt = mixedFrames
                    endFrame = minOf(endFrame, mixedFrames + (rate * 120L / 1000).coerceAtLeast(1))
                }
            }
            val old = if (mixedFrames < endFrame) a else null
            val progress = when {
                mixedFrames >= endFrame -> 1f
                aEndedAt < 0 -> (mixedFrames + 1f) / overlapFrames
                else -> {
                    val start = aEndedAt.toFloat() / overlapFrames
                    start + (1f - start) * (mixedFrames - aEndedAt + 1f) / (endFrame - aEndedAt)
                }
            }
            val gainA = sqrt(1f - progress)
            val gainB = sqrt(progress)
            val headroom = 1f - 0.56f * progress * (1f - progress)
            var peak = 32767f
            for (channel in 0 until channels) {
                var sampleA = if (old == null) 0f else old.samples[aOffset + channel] * outgoingGain
                var sampleB = frame.samples[i + channel] * incomingGain
                if (bassExchange && old != null) {
                    lowA[channel] += (sampleA - lowA[channel]) * lowPass
                    lowB[channel] += (sampleB - lowB[channel]) * lowPass
                    sampleA -= lowA[channel] * (2f * progress - 1f).coerceAtLeast(0f)
                    sampleB -= lowB[channel] * (1f - 2f * progress).coerceAtLeast(0f)
                }
                sum[channel] = (sampleA * gainA + sampleB * gainB) * headroom
                peak = maxOf(peak, abs(sum[channel]))
            }
            val limiter = 32767f / peak
            for (channel in 0 until channels) {
                mixed[i + channel] = (sum[channel] * limiter).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            if (old != null) aOffset += channels
            mixedFrames++
        }
        return AudioDecoder.Frame(mixed, mixed.size, rate, channels)
    }

    fun finish(): AudioDecoder? {
        if (mixedFrames < endFrame) return null
        try { outgoing.close() } catch (_: Exception) {}
        return incoming
    }

    override fun close() {
        try { outgoing.close() } finally { incoming.close() }
    }

    companion object {
        /** Decode the short lead-in so MP3 bit reservoirs and exact sample cues are preserved. */
        fun openIncoming(bytes: ByteArray, cueMs: Long, active: () -> Boolean): AudioDecoder.Started {
            if (cueMs <= 0) return AudioDecoder.start(bytes, 0L, active)
            val decoder = AudioDecoder.open(bytes)
            try {
                var skipped = 0f
                while (active()) {
                    val frame = decoder.read() ?: return AudioDecoder.Started(decoder, skipped, null)
                    if (skipped + frame.durationMs > cueMs) {
                        val frames = ((cueMs - skipped) * frame.rate / 1000).toInt().coerceAtLeast(0)
                        val offset = minOf(frames * frame.channels, frame.length - frame.channels)
                        val samples = frame.samples.copyOfRange(offset, frame.length)
                        val start = skipped + offset * 1000f / (frame.rate * frame.channels)
                        return AudioDecoder.Started(decoder, start, AudioDecoder.Frame(samples, samples.size, frame.rate, frame.channels))
                    }
                    skipped += frame.durationMs
                }
                throw CancellationException()
            } catch (e: Throwable) {
                try { decoder.close() } catch (_: Exception) {}
                throw e
            }
        }
    }
}
