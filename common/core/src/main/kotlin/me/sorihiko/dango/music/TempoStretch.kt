package me.sorihiko.dango.music

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pitch-preserving overlap/add on the outgoing deck. The incoming deck keeps its original clock. */
internal class TempoStretch(
    private val decoder: AudioDecoder,
    first: AudioDecoder.Frame,
    private val speed: Double,
) : AudioDecoder {
    private val rate = first.rate
    private val channels = first.channels
    private val hop = (rate / 50).coerceAtLeast(1) // 20 ms overlap, 40 ms analysis window
    private val radius = (rate * 6 / 1000).coerceAtLeast(1)
    private val buffer = ShortArray((4 * hop + 2 * radius) * channels)
    private val tail = ShortArray(hop * channels)
    private var packet: AudioDecoder.Frame? = first
    private var packetOffset = 0
    private var bufferStart = 0
    private var frames = 0
    private var outputFrames = 0
    private var exhausted = false

    override fun read(): AudioDecoder.Frame? {
        if (exhausted) return null
        val nominal = (outputFrames * speed).roundToInt()
        val start = if (outputFrames == 0) 0 else (nominal - radius).coerceAtLeast(bufferStart)
        val end = if (outputFrames == 0) 0 else nominal + radius
        fill(start, end + 2 * hop)
        val last = minOf(end, bufferStart + frames - 2 * hop)
        if (last < start) {
            exhausted = true
            if (outputFrames != 0) return AudioDecoder.Frame(tail, tail.size, rate, channels)
            val available = frames * channels
            return if (available == 0) null else AudioDecoder.Frame(buffer.copyOf(available), available, rate, channels)
        }

        val window = if (outputFrames == 0) 0 else bestMatch(start, last, nominal)
        val offset = (window - bufferStart) * channels
        val samples = ShortArray(hop * channels)
        for (i in samples.indices) {
            val new = buffer[offset + i]
            val mix = if (outputFrames == 0) 1f else (i / channels + 1f) / hop
            samples[i] = (tail[i] * (1f - mix) + new * mix).roundToInt().toShort()
        }
        buffer.copyInto(tail, 0, offset + samples.size, offset + 2 * samples.size)
        outputFrames += hop
        return AudioDecoder.Frame(samples, samples.size, rate, channels)
    }

    private fun fill(start: Int, end: Int) {
        val discard = minOf(start - bufferStart, frames).coerceAtLeast(0)
        buffer.copyInto(buffer, 0, discard * channels, frames * channels)
        bufferStart += discard
        frames -= discard
        while (bufferStart + frames < end) {
            val current = packet ?: decoder.read()?.also { packet = it } ?: break
            if (current.rate != rate || current.channels != channels) break
            val count = minOf((current.length - packetOffset) / channels, end - bufferStart - frames)
            current.samples.copyInto(buffer, frames * channels, packetOffset, packetOffset + count * channels)
            frames += count
            packetOffset += count * channels
            if (packetOffset == current.length) {
                packet = null
                packetOffset = 0
            }
        }
    }

    private fun bestMatch(first: Int, last: Int, nominal: Int): Int {
        var best = nominal.coerceIn(first, last)
        var score = -Double.MAX_VALUE
        val step = (rate / 14_000).coerceAtLeast(1)
        val stride = (rate / 5_500).coerceAtLeast(1) * channels
        for (candidate in first..last step step) {
            val offset = (candidate - bufferStart) * channels
            var product = 0.0
            var oldEnergy = 0.0
            var newEnergy = 0.0
            for (i in tail.indices step stride) {
                val a = tail[i].toDouble()
                val b = buffer[offset + i].toDouble()
                product += a * b
                oldEnergy += a * a
                newEnergy += b * b
            }
            val correlation = product / sqrt(oldEnergy * newEnergy + 1.0)
            val weighted = correlation - 0.025 * abs(candidate - nominal) / radius
            if (weighted > score) {
                score = weighted
                best = candidate
            }
        }
        return best
    }

    override fun close() = decoder.close()
}
