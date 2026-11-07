package me.sorihiko.dango.music

import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/** Streams the original EchoMusic two-deck mixer, including template automation and effect tails. */
internal class TrackBlend(
    private val outgoing: AudioDecoder,
    outgoingHead: AudioDecoder.Frame,
    private val incoming: AudioDecoder,
    incomingHead: AudioDecoder.Frame,
    plan: TrackTransition.Plan,
    outgoingGain: Float,
    incomingGain: Float,
    preroll: FloatArray,
) : AudioDecoder {
    private val rate = incomingHead.rate
    private val channels = incomingHead.channels
    private var handle = EchoTransitionNative.createMixer(plan.json, rate, channels, outgoingGain, incomingGain)
    private var pendingA: AudioDecoder.Frame? = outgoingHead
    private var pendingB: AudioDecoder.Frame? = incomingHead
    private var remainingA = ((plan.endMs - plan.exitMs).coerceAtLeast(0L) * rate / 1000) * channels
    private var endedA = false
    private var endedB = false
    private var released = false
    private var restored: AudioDecoder? = null

    init {
        val wanted = (EchoTransitionNative.info(handle) shr 34 and 65535).toInt() * channels
        if (wanted > 0 && preroll.isNotEmpty()) {
            EchoTransitionNative.preroll(handle, preroll.copyOfRange((preroll.size - wanted).coerceAtLeast(0), preroll.size))
        }
    }

    override fun read(): AudioDecoder.Frame? {
        check(!released)
        while (true) {
            val info = EchoTransitionNative.info(handle)
            if (info and 1L != 0L) return null
            if (!endedA && (info shr 2 and 65535) < 4096) {
                val frame = if (remainingA <= 0) null else pendingA ?: try {
                    outgoing.read()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                pendingA = null
                if (frame == null) {
                    EchoTransitionNative.push(handle, 0, null)
                    endedA = true
                } else {
                    check(frame.rate == rate && frame.channels == channels)
                    val length = minOf(frame.length.toLong(), remainingA).toInt()
                    EchoTransitionNative.push(handle, 0, FloatArray(length) { frame.samples[it] / 32768f })
                    remainingA -= length
                }
            }
            if (!endedB && (info shr 18 and 65535) < 4096) {
                val frame = pendingB ?: incoming.read()
                pendingB = null
                if (frame == null) {
                    EchoTransitionNative.push(handle, 1, null)
                    endedB = true
                } else {
                    check(frame.rate == rate && frame.channels == channels)
                    EchoTransitionNative.push(handle, 1, FloatArray(frame.length) { frame.samples[it] / 32768f })
                }
            }
            val samples = EchoTransitionNative.render(handle, 2048)
            if (samples.isNotEmpty()) return pcm(samples)
            if (endedA && endedB) return null
        }
    }

    fun finish(): AudioDecoder? {
        restored?.let { return it }
        if (EchoTransitionNative.info(handle) and 1L == 0L) return null
        val remaining = EchoTransitionNative.remainder(handle)
        EchoTransitionNative.release(handle)
        released = true
        try { outgoing.close() } catch (_: Exception) {}
        val head = if (remaining.isEmpty()) null else pcm(remaining)
        return object : AudioDecoder {
            private var pending = head
            override fun read(): AudioDecoder.Frame? {
                val frame = pending
                pending = null
                return frame ?: incoming.read()
            }
            override fun close() = incoming.close()
        }.also { restored = it }
    }

    private fun pcm(samples: FloatArray): AudioDecoder.Frame {
        val shorts = ShortArray(samples.size) {
            (samples[it] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return AudioDecoder.Frame(shorts, shorts.size, rate, channels)
    }

    override fun close() {
        if (!released) {
            EchoTransitionNative.release(handle)
            released = true
            try { outgoing.close() } finally { incoming.close() }
        } else {
            restored?.close()
        }
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
