package me.sorihiko.dango.music

import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.roundToInt

/** Feeds one PCM stream to the existing output while two compatible decoders overlap. */
internal class TrackBlend(
    private val outgoing: AudioDecoder,
    outgoingHead: AudioDecoder.Frame,
    private val incoming: AudioDecoder,
    incomingHead: AudioDecoder.Frame,
    overlapMs: Long,
    private val outgoingGain: Float,
    private val incomingGain: Float,
    private val bassExchange: Boolean,
) : AudioDecoder {
    private val rate = incomingHead.rate
    private val channels = incomingHead.channels
    private val overlapFrames = (overlapMs * rate / 1000).coerceAtLeast(1)
    private var mixedFrames = 0L
    private var a: AudioDecoder.Frame? = outgoingHead
    private var aOffset = 0
    private var b: AudioDecoder.Frame? = incomingHead
    private val lowPass = (1.0 - exp(-2.0 * Math.PI * 180 / rate)).toFloat()
    private val lowA = FloatArray(channels)
    private val lowB = FloatArray(channels)

    override fun read(): AudioDecoder.Frame? {
        val frame = b ?: incoming.read() ?: return null
        b = null
        check(frame.rate == rate && frame.channels == channels) { "Incoming audio format changed during transition" }
        val mixed = ShortArray(frame.length)
        for (i in mixed.indices step channels) {
            if (mixedFrames < overlapFrames && a?.let { aOffset == it.length } == true) {
                a = outgoing.read()?.takeIf { it.rate == rate && it.channels == channels }
                aOffset = 0
            }
            val old = if (mixedFrames < overlapFrames) a else null
            val progress = if (old == null) 1f else (mixedFrames + 1f) / overlapFrames
            for (channel in 0 until channels) {
                var sampleA = if (old == null) 0f else old.samples[aOffset + channel] * outgoingGain
                var sampleB = frame.samples[i + channel] * incomingGain
                if (bassExchange && old != null) {
                    lowA[channel] += (sampleA - lowA[channel]) * lowPass
                    lowB[channel] += (sampleB - lowB[channel]) * lowPass
                    sampleA -= lowA[channel] * (2f * progress - 1f).coerceAtLeast(0f)
                    sampleB -= lowB[channel] * (1f - 2f * progress).coerceAtLeast(0f)
                }
                val sample = sampleA * (1f - progress) + sampleB * progress
                mixed[i + channel] = sample.roundToInt().coerceIn(-32768, 32767).toShort()
            }
            if (old != null) aOffset += channels
            mixedFrames++
        }
        return AudioDecoder.Frame(mixed, mixed.size, rate, channels)
    }

    fun finish(): AudioDecoder? {
        if (mixedFrames < overlapFrames) return null
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
