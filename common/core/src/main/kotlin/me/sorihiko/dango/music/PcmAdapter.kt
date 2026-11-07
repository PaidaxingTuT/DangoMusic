package me.sorihiko.dango.music

/** Converts a prepared track to the open output's format, retaining interpolation across decoder frames. */
internal class PcmAdapter(
    private val decoder: AudioDecoder,
    first: AudioDecoder.Frame,
    private val rate: Int,
    private val channels: Int,
) : AudioDecoder {
    private val inputRate = first.rate
    private val inputChannels = first.channels
    private var pending: AudioDecoder.Frame? = first
    private var previousLeft = 0
    private var previousRight = 0
    private var started = false
    private var ended = false
    // Position of the next output sample relative to the previous input sample, in 1/rate units.
    private var phase = 0

    init {
        require(inputRate > 0 && rate > 0 && inputChannels in 1..2 && channels in 1..2)
    }

    override fun read(): AudioDecoder.Frame? {
        while (!ended) {
            val frame = pending ?: decoder.read()
            pending = null
            if (frame == null) {
                ended = true
                if (!started || phase >= rate) return null
                val count = (rate - phase + inputRate - 1) / inputRate
                val tail = ShortArray(count * channels) {
                    (if (it % channels == 0) previousLeft else previousRight).toShort()
                }
                return AudioDecoder.Frame(tail, tail.size, rate, channels)
            }
            check(frame.rate == inputRate && frame.channels == inputChannels) { "Audio format changed during conversion" }
            val inputFrames = frame.length / inputChannels
            val capacity = ((inputFrames.toLong() * rate + inputRate - 1) / inputRate + 1).toInt()
            val samples = ShortArray(capacity * channels)
            var length = 0
            for (offset in 0 until frame.length step inputChannels) {
                val left = frame.samples[offset].toInt()
                val right = if (inputChannels == 2) frame.samples[offset + 1].toInt() else left
                val nextLeft = if (channels == 1) (left + right) / 2 else left
                if (!started) {
                    started = true
                    samples[length++] = nextLeft.toShort()
                    if (channels == 2) samples[length++] = right.toShort()
                    phase = inputRate
                } else {
                    while (phase <= rate) {
                        val fraction = phase.toFloat() / rate
                        samples[length++] = (previousLeft + (nextLeft - previousLeft) * fraction).toInt().toShort()
                        if (channels == 2) samples[length++] = (previousRight + (right - previousRight) * fraction).toInt().toShort()
                        phase += inputRate
                    }
                    phase -= rate
                }
                previousLeft = nextLeft
                previousRight = right
            }
            if (length != 0) return AudioDecoder.Frame(samples, length, rate, channels)
        }
        return null
    }

    override fun close() = decoder.close()
}
