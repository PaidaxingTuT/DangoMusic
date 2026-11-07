package me.sorihiko.dango.music

import me.sorihiko.dango.DangoLogger

import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.max

/**
 * 内置网易云播放器：下载 mp3 → JLayer 解码 → Java Sound 输出（安卓等不带声音库的 Java 改用游戏的 OpenAL，见 [AudioOutput]）。
 * 播放进度 = 起始偏移 + 声卡实际已经播放的时长，所以歌词同步是精确的，拖动进度条也没问题。
 */
object MusicPlayer {

    enum class State { STOPPED, LOADING, PLAYING, PAUSED, ERROR }

    @Volatile var state = State.STOPPED
        private set
    @Volatile var current: NeteaseTrack? = null
        private set
    @Volatile var message: String = ""
        private set

    @Volatile var queue: List<NeteaseTrack> = emptyList()
        private set
    @Volatile var index = -1
        private set

    @Volatile var shuffle = false
    /** 歌曲自然播完时重播当前曲目；手动切歌仍按队列 / Shuffle 工作。 */
    @Volatile var repeatOne = false
    @Volatile var transitionMode = TrackTransitionMode.OFF
    @Volatile var transitionSeconds = 7
    @Volatile var transitionOnNext = true
    @Volatile var transitionTempoMatch = true
    /** 0..1 */
    @Volatile var volume = 0.2f

    /** 声音从哪里放：0 自动（安卓用游戏的 OpenAL，电脑用 Java Sound），1 Java Sound，2 游戏的 OpenAL。换了就从当前位置重新开始放 */
    @Volatile var outputMode = OUTPUT_AUTO
        set(v) {
            if (field == v) return
            field = v
            if (state == State.PLAYING) seekLocal(positionMs)
        }

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicLoader").apply { isDaemon = true } }
    private val preloader = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicPreloader").apply { isDaemon = true } }
    private val radioWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-MusicRadio").apply { isDaemon = true } }

    /** 电台式的队列（私人 FM）：快放完时用它再要几首接在后面；普通歌单是 null */
    @Volatile var radio: (() -> List<NeteaseTrack>)? = null
        private set
    private val random = Random()

    /** 每次换歌 / 拖动都 +1，旧的解码线程看到代数变了就自己退出 */
    @Volatile private var generation = 0
    @Volatile private var audio: ByteArray? = null
    @Volatile private var line: AudioOutput? = null
    @Volatile private var seekBaseMs = 0L
    @Volatile private var pausedAtMs = 0L
    @Volatile private var preparedForSeek: PreparedNext? = null
    @Volatile private var manualNext: ManualNext? = null
    private var shuffleNext: ShuffleNext? = null
    @Volatile var lastTransition: TransitionResult? = null
        private set
    val transitionPending: Boolean get() = manualNext != null

    data class TransitionResult(val mode: TrackTransitionMode, val overlapMs: Long, val speed: Double, val atNanos: Long)
    private class ManualNext(val generation: Int, val index: Int, val deadline: Long)
    private class ShuffleNext(val from: NeteaseTrack, val index: Int, val track: NeteaseTrack)

    val durationMs: Long get() = current?.durationMs ?: 0L

    // ---------------------------------------------------------------- 鼓点检测（文闪用）

    /** (歌曲内时间 ms, 强度 0..1)，解码时根据低频能量突增检测出来 */
    private class Beat(val timeMs: Long, val strength: Float)
    private val beats = ArrayDeque<Beat>()

    /**
     * 当前的"鼓点冲击"强度：最近一个鼓点之后迅速衰减，0..1。
     * posMs 用歌曲内时间（和歌词同一条时间线）。
     */
    fun beatPulse(posMs: Long): Float {
        var best = 0f
        synchronized(beats) {
            for (b in beats) {
                val dt = posMs - b.timeMs
                if (dt < 0 || dt > 600) continue
                best = max(best, b.strength * kotlin.math.exp(-dt / 110f))
            }
        }
        return best
    }

    private fun clearBeats() = synchronized(beats) { beats.clear() }

    /** 最近检测到的鼓点：[0] 时间（歌曲内 ms），[1] 强度 0..1，从早到晚 */
    fun beatSnapshot(): Array<FloatArray> = synchronized(beats) {
        Array(beats.size) { val b = beats.elementAt(it); floatArrayOf(b.timeMs.toFloat(), b.strength) }
    }

    val positionMs: Long
        get() = when (state) {
            State.PLAYING -> (seekBaseMs + (line?.positionMicros ?: 0L) / 1000).coerceAtLeast(0L)
            State.PAUSED -> pausedAtMs
            else -> 0L
        }

    val active: Boolean get() = current != null && (state == State.PLAYING || state == State.PAUSED || state == State.LOADING)

    // ---------------------------------------------------------------- 队列控制

    private val party get() = me.sorihiko.dango.party.ListenParty

    /** 一起听：房主关了"允许成员控制"时被拦下的时间（界面据此提示一下） */
    @Volatile var blockedNotice = 0L
        private set

    /**
     * 一起听的成员、房主没开"允许成员控制"：不能自己切歌 / 暂停 / 拖进度（只跟着房主听），拦下来并提示。
     * 跟随房主用的是 [playTrackAt] / [syncTo]，不经过这里
     */
    private fun lockedByHost(): Boolean {
        if (party.role != me.sorihiko.dango.party.ListenParty.Role.MEMBER || party.canControl) return false
        blockedNotice = System.nanoTime()
        return true
    }

    @Synchronized @JvmOverloads fun playQueue(tracks: List<NeteaseTrack>, startIndex: Int, radio: (() -> List<NeteaseTrack>)? = null) {
        if (lockedByHost()) return
        if (tracks.isNotEmpty() && !canPlay(tracks[Math.floorMod(startIndex, tracks.size)])) return
        // 一起听的成员：整个列表交给房主去放（房主换成这个列表，从这首开始）
        if (party.canControl) {
            if (tracks.isNotEmpty()) party.requestPlayList(tracks, Math.floorMod(startIndex, tracks.size))
            return
        }
        queue = tracks
        this.radio = radio
        playIndex(startIndex)
    }

    private val failedTracks = HashSet<String>()
    private val failureTimer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Dango-Skip").apply { isDaemon = true } }
    @Volatile var skipNotice = 0L
        private set

    @Synchronized fun playIndex(i: Int) {
        if (lockedByHost()) return
        failedTracks.clear()
        playIndexInternal(i)
    }

    private fun playIndexInternal(i: Int) {
        val q = queue
        if (q.isEmpty()) return
        val idx = ((i % q.size) + q.size) % q.size
        val track = q[idx]
        if (!canPlay(track)) return
        if (party.canControl) return party.requestPlay(track)
        index = idx
        val gen = ++generation
        stopLine()
        clearTransition()
        audio = null
        current = track
        state = State.LOADING
        message = "加载中…"
        loader.execute { load(gen, track) }
        if (radio != null && idx >= q.size - 2) extendRadio(null)
    }

    /** 电台：再要几首接到队列后面（去掉已经在队列里的），然后执行 [then] */
    private fun extendRadio(then: (() -> Unit)?) {
        val fetch = radio ?: return
        radioWorker.execute {
            if (radio !== fetch) return@execute
            // 已经有好几首没放的就不用再要了（连续调用时只要一次）
            if (queue.size - index <= 2) {
                val more = try { fetch() } catch (e: Exception) {
                    DangoLogger.warn("Radio fetch failed: {}", e.toString())
                    emptyList()
                }
                if (radio !== fetch) return@execute
                val have = queue.mapTo(HashSet()) { it.key }
                val fresh = more.filter { have.add(it.key) }
                if (fresh.isNotEmpty()) queue = queue + fresh
            }
            then?.invoke()
        }
    }

    /**
     * 歌单还在加载时就开始放了：加载出更多后把队列换成更长的版本。
     * 只在队列还是 [from] 本身、并且 [to] 以它为开头时才换，当前这首和播放位置都不变。
     */
    fun growQueue(from: List<NeteaseTrack>, to: List<NeteaseTrack>) {
        if (queue !== from || radio != null || to.size <= from.size) return
        for (i in from.indices) if (from[i].key != to[i].key) return
        queue = to
    }

    /** 一起听：正在跟随房主（歌放完不自动下一首，等房主切） */
    @Volatile var followMode = false

    private fun canPlay(track: NeteaseTrack): Boolean {
        if (party.role != me.sorihiko.dango.party.ListenParty.Role.NONE && CloudDrives.privateTrack(track)) {
            message = "Private Kugou cloud tracks cannot be shared in Listen Together. Leave the room to play them."
            return false
        }
        if (me.sorihiko.dango.party.ListenParty.role == me.sorihiko.dango.party.ListenParty.Role.NONE || me.sorihiko.dango.party.ListenParty.supportsSource(track.source)) return true
        message = "This source is unavailable in this room. Create a new room with the updated mod."
        return false
    }

    /**
     * 一起听：跟随房主播放某首歌。
     * 起始位置在下载完成、真正开始播放前才计算（下载要好几秒，提前算会落后）。
     */
    @Synchronized fun playTrackAt(track: NeteaseTrack, startAt: () -> Long, paused: Boolean) {
        if (!canPlay(track)) return
        queue = listOf(track)
        radio = null
        index = 0
        val gen = ++generation
        stopLine()
        clearTransition()
        audio = null
        current = track
        state = State.LOADING
        message = "加载中…"
        loader.execute { load(gen, track, startAt, paused) }
    }

    /** 一起听：房主放成员点的歌，插在当前这首后面，放完接着原来的队列 */
    @Synchronized fun playNext(track: NeteaseTrack) {
        val q = queue
        if (q.isEmpty() || index !in q.indices) {
            queue = listOf(track)
            playIndex(0)
            return
        }
        queue = q.subList(0, index + 1) + track + q.subList(index + 1, q.size)
        playIndex(index + 1)
    }

    /** 一起听：跳到 ms 并设成播放 / 暂停（当前这首已经下载好时用） */
    @Synchronized fun syncTo(ms: Long, play: Boolean) {
        val bytes = audio ?: return
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        pausedAtMs = target
        state = if (play) State.PLAYING else State.PAUSED   // 解码线程据此决定声卡是开着还是停着
        val gen = ++generation
        stopLine()
        startDecode(gen, bytes, target)
    }

    @Synchronized fun next() {
        if (lockedByHost()) return
        if (party.canControl) return party.requestNext()
        if (transitionOnNext && transitionMode.overlaps && state == State.PLAYING && line != null &&
            canTransition(manual = true) && manualNext == null) {
            val target = nextTransitionIndex()
            if (target >= 0) {
                manualNext = ManualNext(generation, target, System.nanoTime() + 3_000_000_000L)
                return
            }
        }
        advanceQueue()
    }

    private fun advanceQueue() {
        if (lockedByHost()) return
        if (party.canControl) return party.requestNext()
        val q = queue
        if (q.isEmpty()) return
        val planned = manualNext?.takeIf { it.generation == generation }?.index
            ?: if (canTransition()) nextTransitionIndex() else -1
        if (planned in q.indices) {
            playIndex(planned)
            return
        }
        // 电台按顺序往下放；放到最后一首时先要几首新的再切
        if (radio != null) {
            if (index + 1 < q.size) playIndex(index + 1)
            else { val from = index; extendRadio { if (index == from) playIndex(from + 1) } }
            return
        }
        playIndex(if (shuffle && q.size > 1) pickRandom(q.size) else index + 1)
    }

    private fun advanceWithTransitionFallback() {
        val showFallback = manualNext != null || (canTransition() && nextTransitionIndex() >= 0)
        val previousGeneration = generation
        advanceQueue()
        if (showFallback && generation != previousGeneration) {
            lastTransition = TransitionResult(TrackTransitionMode.OFF, 0L, 1.0, System.nanoTime())
        }
    }

    fun prev() {
        if (lockedByHost()) return
        if (party.canControl) return party.requestPrev()
        if (positionMs > 3000) {
            seek(0)
            return
        }
        playIndex(index - 1)
    }

    private fun pickRandom(size: Int): Int {
        var r: Int
        do { r = random.nextInt(size) } while (r == index)
        return r
    }

    @Synchronized fun togglePause() {
        if (lockedByHost()) return
        // 一起听的成员：本地先生效，再告诉房主
        if (party.canControl && (state == State.PLAYING || state == State.PAUSED)) {
            party.requestState(if (state == State.PLAYING) me.sorihiko.dango.party.PartyRequest.PAUSE
                else me.sorihiko.dango.party.PartyRequest.RESUME, positionMs)
        }
        when (state) {
            State.PLAYING -> {
                pausedAtMs = positionMs
                state = State.PAUSED
                line?.stop()
            }
            State.PAUSED -> {
                val l = line
                if (l != null) {
                    // 声卡输出只是停住了，接着放就行；解码线程看到状态变回 PLAYING 会继续写数据
                    state = State.PLAYING
                    l.start()
                } else {
                    seekLocal(pausedAtMs)
                }
            }
            else -> {}
        }
    }

    fun seek(ms: Long) {
        if (audio == null) return
        if (lockedByHost()) return
        if (party.canControl) party.requestState(me.sorihiko.dango.party.PartyRequest.SEEK, ms.coerceIn(0L, max(0L, durationMs - 500)))
        seekLocal(ms)
    }

    @Synchronized private fun seekLocal(ms: Long) {
        val bytes = audio ?: return
        val target = ms.coerceIn(0L, max(0L, durationMs - 500))
        // 暂停时拖动：停在新位置，仍保持暂停
        if (state == State.PAUSED) pausedAtMs = target
        val gen = ++generation
        manualNext = null
        lastTransition = null
        stopLine()
        startDecode(gen, bytes, target)
    }

    @Synchronized fun stop() {
        ++generation
        stopLine()
        clearTransition()
        audio = null
        current = null
        state = State.STOPPED
        message = ""
    }

    // ---------------------------------------------------------------- 加载 / 解码

    private class LoadedTrack(val bytes: ByteArray, val durationMs: Long, val gain: Float, val message: String)
    private class PreparedNext(
        val from: NeteaseTrack, val index: Int, val track: NeteaseTrack, val loaded: LoadedTrack,
        val mode: TrackTransitionMode, val seconds: Int, val matchTempo: Boolean, val plan: TrackTransition.Plan?,
    )

    private fun loadTrack(track: NeteaseTrack, active: () -> Boolean): LoadedTrack {
        val kugou = if (track.source == MusicSource.KUGOU) KugouMusicSource.loadAudio(track, active) else null
        val url = if (track.source == MusicSource.KUGOU) kugou?.info else MusicSources.api(track.source).songUrl(track)
        if (!active()) throw CancellationException()
        if (url == null) throw SourceAccessException(if (track.source == MusicSource.NETEASE) "拿不到播放地址"
            else if (NativeAccounts.get(track.source) != null) "No playable MP3. Your login may have expired, or this song is restricted by membership or region."
            else "No guest MP3 available. Login, payment or regional restrictions may apply.")
        val bytes = kugou?.bytes ?: if (track.source == MusicSource.NETEASE) NeteaseApi.request(url.url)
            else SourceHttp.bytes(url.url, if (track.source == MusicSource.QQ) "https://y.qq.com/" else "https://m.kugou.com/")
        if (!active()) throw CancellationException()
        if (bytes == null || bytes.isEmpty() || (!track.cloud && bytes.size < 10_000)) {
            throw SourceAccessException(if (track.source != MusicSource.NETEASE) "Unable to download this guest MP3. Try another song."
                else if (NeteaseApi.loggedIn) "这首歌没有播放权限" else "需要登录才能播放（VIP 歌曲）")
        }
        // Cloud list duration units are not reliable. Use compressed audio timing before enabling seeking.
        val measuredDuration = if (track.cloud || transitionMode != TrackTransitionMode.OFF) AudioAnalysis.duration(bytes, active) else 0L
        val measuredGain = if (normalize) AudioAnalysis.gain(bytes, active) else null
        val gain = if (normalize) measuredGain ?: loudnessGain(url) else 1f
        val message = when {
            url.trialOnly -> "Preview only: membership is needed for the full song"
            url.replaced -> "The original was unavailable, playing another version"
            else -> ""
        }
        DangoLogger.info("Audio analysis: source={} cloud={} durationMs={} localGain={} appliedGain={}",
            track.source.key, track.cloud, measuredDuration, measuredGain != null, gain)
        return LoadedTrack(bytes, measuredDuration, gain, message)
    }

    private fun applyLoaded(track: NeteaseTrack, loaded: LoadedTrack) {
        if (loaded.durationMs > 0) track.durationMs = loaded.durationMs
        audio = loaded.bytes
        message = loaded.message
        trackGain = loaded.gain
    }

    private fun canTransition(manual: Boolean = false) = transitionMode != TrackTransitionMode.OFF &&
        (manual || !repeatOne) && !followMode &&
        party.role == me.sorihiko.dango.party.ListenParty.Role.NONE

    private fun clearTransition() {
        preparedForSeek = null
        manualNext = null
        shuffleNext = null
        lastTransition = null
    }

    @Synchronized private fun nextTransitionIndex(): Int {
        val list = queue
        val from = current ?: return -1
        if (list.size <= 1 || index !in list.indices) return -1
        if (radio != null) return (index + 1).takeIf { it in list.indices } ?: -1
        if (!shuffle) return (index + 1) % list.size
        val reserved = shuffleNext
        if (reserved?.from === from && list.getOrNull(reserved.index) === reserved.track && reserved.index != index) return reserved.index
        val next = pickRandom(list.size)
        shuffleNext = ShuffleNext(from, next, list[next])
        return next
    }

    private fun load(gen: Int, track: NeteaseTrack, startAt: (() -> Long)? = null, paused: Boolean = false) {
        try {
            val loaded = loadTrack(track) { gen == generation }
            if (gen != generation) return
            applyLoaded(track, loaded)
            val start = (startAt?.invoke() ?: 0L).coerceIn(0L, max(0L, track.durationMs - 1000))
            if (paused) {
                pausedAtMs = start
                state = State.PAUSED
            }
            startDecode(gen, loaded.bytes, start)
        } catch (e: Throwable) {
            if (gen != generation) return
            // 包括 NoClassDefFoundError 之类的 Error：不接住的话线程会悄悄退出，界面一直停在"加载中"
            if (track.source == MusicSource.NETEASE) {
                DangoLogger.warn("Music load failed: {}", e.javaClass.simpleName)
                fail(gen, if (e is SourceAccessException) e.message.orEmpty() else "加载失败：" + describe(e))
            } else {
                DangoLogger.warn("Source {} load failed: {}", track.source.key, e.javaClass.simpleName)
                fail(gen, if (e is SourceAccessException) e.message.orEmpty() else "Playback request failed. Check your connection or log in again.")
            }
        }
    }

    /** 失败跳过独立计时，不占用加载线程；旧请求不能切掉用户新选的歌。 */
    @Synchronized private fun fail(gen: Int, msg: String) {
        if (gen != generation) return
        state = State.ERROR
        message = msg
        if (followMode || party.role == me.sorihiko.dango.party.ListenParty.Role.MEMBER) return
        current?.let { failedTracks.add(it.key) }
        if (failedTracks.size >= 10 || queue.none { it.key !in failedTracks }) {
            message = msg + " · " + "Auto-skip stopped after repeated playback failures."
            return
        }
        failureTimer.schedule({
            synchronized(this) {
                if (gen == generation && state == State.ERROR && failedTracks.size < 10 && !followMode && party.role != me.sorihiko.dango.party.ListenParty.Role.MEMBER) {
                    val candidates = queue.indices.filter { queue[it].key !in failedTracks }
                    if (candidates.isNotEmpty()) {
                        val next = if (shuffle) candidates[random.nextInt(candidates.size)]
                            else candidates.firstOrNull { it > index } ?: candidates.first()
                        skipNotice = System.nanoTime()
                        playIndexInternal(next)
                    }
                }
            }
        }, 3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun describe(e: Throwable): String {
        val m = e.message?.take(60)
        return e.javaClass.simpleName + if (m.isNullOrBlank()) "" else ": $m"
    }

    /**
     * 打开声卡输出。默认设备打不开时（被别的程序独占、格式不支持等），
     * 依次试系统里其它能输出这个格式的设备。
     */
    /** 测试用：-Ddangomusic.openal=true 时电脑上也用游戏的 OpenAL 放歌 */
    private val forceOpenAl = java.lang.Boolean.getBoolean("dangomusic.openal")
    private var loggedOutput = ""

    /** 这次该先试游戏的 OpenAL 吗 */
    private fun preferGameAudio(): Boolean = when (outputMode) {
        OUTPUT_JAVA_SOUND -> false
        OUTPUT_GAME -> true
        // 安卓上 Java Sound 是模拟出来的，放歌会掉帧；电脑上 Java Sound 不占游戏的声音通道，更稳
        else -> forceOpenAl || AndroidRuntime.detected || !AudioOutput.javaSoundNative
    }

    /**
     * 打开声音输出：按 [outputMode] 先试游戏的 OpenAL 或 Java Sound，打不开再换另一个。
     */
    private fun openOutput(format: AudioFormat, bufferBytes: Int): AudioOutput {
        if (preferGameAudio()) platformOutput(format)?.let { return it }
        return try {
            JavaSoundOutput(openLine(format, bufferBytes)).also { logOutput("Java Sound") }
        } catch (e: Throwable) {
            platformOutput(format) ?: throw e
        }
    }

    /**
     * 声音系统重启后重新打开输出。游戏要一会儿才能把声音系统开回来，最多等几秒；
     * 原来用的是游戏的 OpenAL 就继续等它，等不到再按平常的顺序打开。
     */
    private fun reopenOutput(gen: Int, old: AudioOutput, rate: Int, channels: Int): AudioOutput? {
        DangoLogger.info("Dango Music: game audio restarted, reopening output")
        try { old.close() } catch (_: Exception) {}
        val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
        var out: AudioOutput? = null
        val deadline = System.currentTimeMillis() + 5000L
        while (out == null && gen == generation && System.currentTimeMillis() < deadline) {
            out = platformOutput(format)
            if (out == null) Thread.sleep(100)
        }
        if (gen != generation) {
            try { out?.close() } catch (_: Exception) {}
            return null
        }
        if (out == null) out = openOutput(format, rate * channels * 2 / 4)
        line = out
        if (state == State.PAUSED) out.stop() else out.start()
        return out
    }

    private fun platformOutput(format: AudioFormat): AudioOutput? = try {
        me.sorihiko.dango.DangoMusic.instance.platform.openAudioOutput(format.sampleRate.toInt(), format.channels)
            ?.also { logOutput("game OpenAL") }
    } catch (e: Throwable) {
        DangoLogger.warn("Game audio output failed: {}", e.toString())
        null
    }

    private fun logOutput(name: String) {
        if (loggedOutput == name) return
        loggedOutput = name
        DangoLogger.info("Dango Music: playing through {}", name)
    }

    private fun openLine(format: AudioFormat, bufferBytes: Int): SourceDataLine {
        var last: Throwable? = null
        try {
            val l = AudioSystem.getSourceDataLine(format)
            try {
                l.open(format, bufferBytes)
            } catch (e: Exception) {
                l.open(format)
            }
            return l
        } catch (e: Throwable) {
            last = e
            DangoLogger.warn("Default audio line failed: {}", e.toString())
        }
        val info = DataLine.Info(SourceDataLine::class.java, format)
        for (mi in AudioSystem.getMixerInfo()) {
            try {
                val mixer = AudioSystem.getMixer(mi)
                if (!mixer.isLineSupported(info)) continue
                val l = mixer.getLine(info) as SourceDataLine
                l.open(format)
                DangoLogger.info("Using audio device: {}", mi.name)
                return l
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: IllegalStateException("没有可用的声音输出设备")
    }

    /** 音量均衡（设置里开关）：按音源给的响度把每首歌调到差不多响 */
    @Volatile var normalize = true
    /** 这首歌要乘的倍数（1 = 原样） */
    @Volatile private var trackGain = 1f

    private fun loudnessGain(url: NeteaseApi.SongUrl): Float {
        if (!normalize || url.gainDb == 0f) return 1f
        var gain = Math.pow(10.0, url.gainDb / 20.0).toFloat()
        // 有峰值就别把峰值推过 1（会破音）
        if (url.peak > 0f) gain = minOf(gain, 0.98f / url.peak)
        return gain.coerceIn(0.25f, 2f)
    }

    private fun startDecode(gen: Int, bytes: ByteArray, startMs: Long) {
        Thread({ decodeLoop(gen, bytes, startMs) }, "Dango-MusicDecode").apply {
            isDaemon = true
            start()
        }
    }

    private fun decodeLoop(gen: Int, bytes: ByteArray, startMs: Long) {
        var out: AudioOutput? = null
        var finished = false
        var source: AudioDecoder? = null
        var prefetch: Future<PreparedNext>? = null
        var prepareMode = transitionMode
        var prepareSeconds = transitionSeconds
        var prepareTempo = transitionTempoMatch
        var prepareShuffle = shuffle
        var prepared = preparedForSeek?.takeIf {
            it.mode == prepareMode && it.seconds == prepareSeconds && it.matchTempo == prepareTempo &&
                it.from === current && it.index == nextTransitionIndex() &&
                queue.getOrNull(it.index) === it.track
        }
        var requested = prepared != null
        var blendRejected = false
        var handoff: PreparedNext? = null
        var handoffPlan: TrackTransition.Plan? = null
        var boundaryMicros = 0L
        var incomingStartMs = 0L

        fun writePcm(output: AudioOutput, data: ByteArray) {
            var offset = 0
            while (offset < data.size && gen == generation) {
                if (state == State.PAUSED) {
                    if (output.isRunning) output.stop()
                    Thread.sleep(10)
                    continue
                }
                val written = output.write(data, offset, data.size - offset)
                if (written == 0 && gen == generation) {
                    check(!output.lost) { "Audio output was lost" }
                    Thread.sleep(5)
                }
                offset += written
            }
        }

        fun publishHandoff(positionMicros: Long) {
            val next = handoff ?: return
            synchronized(this) {
                if (gen != generation) return
                seekBaseMs = incomingStartMs - boundaryMicros / 1000
                if (state == State.PAUSED) pausedAtMs = seekBaseMs + positionMicros / 1000
                applyLoaded(next.track, next.loaded)
                index = next.index
                current = next.track
                shuffleNext = null
                manualNext = null
                val plan = handoffPlan
                lastTransition = TransitionResult(plan?.mode ?: TrackTransitionMode.GAPLESS,
                    plan?.overlapMs ?: 0L, plan?.speed ?: 1.0, System.nanoTime())
                if (radio != null && index >= queue.size - 2) extendRadio(null)
                failedTracks.clear()
                clearBeats()
                handoff = null
                handoffPlan = null
            }
        }

        try {
            val started = AudioDecoder.start(bytes, startMs) { gen == generation }
            source = started.decoder
            val skipped = started.skippedMs
            val first = started.first
            if (gen != generation) return
            seekBaseMs = skipped.toLong()
            clearBeats()
            EnvironmentAudio.reset()
            var decodedMs = skipped
            var lowPass = 0f
            var avgEnergy = 0f
            var lastBeatMs = -1000f

            var appliedVolume = -1f
            var outputRate = 0
            var outputChannels = 0
            var writtenFrames = 0L
            var pending = first
            while (gen == generation) {
                (source as? TrackBlend)?.finish()?.let { source = it }
                val frame = pending ?: source!!.read()
                pending = null
                if (prepareMode != transitionMode || prepareSeconds != transitionSeconds ||
                    prepareTempo != transitionTempoMatch || prepareShuffle != shuffle) {
                    prefetch?.cancel(true)
                    prefetch = null
                    prepared = null
                    requested = false
                    blendRejected = false
                    prepareMode = transitionMode
                    prepareSeconds = transitionSeconds
                    prepareTempo = transitionTempoMatch
                    prepareShuffle = shuffle
                }
                prefetch?.takeIf { it.isDone }?.let {
                    val result = runCatching { it.get() }.getOrElse { error ->
                        DangoLogger.info("Dango Music transition: {} preload failed ({})", prepareMode, error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName)
                        null
                    }
                    prepared = result
                    synchronized(this) { if (gen == generation) preparedForSeek = result }
                    prefetch = null
                }
                val next = prepared
                val manual = manualNext?.takeIf { it.generation == gen && handoff == null }
                if (manual != null && (!canTransition(manual = true) || !transitionOnNext ||
                        !transitionMode.overlaps || System.nanoTime() >= manual.deadline)) {
                    synchronized(this) {
                        if (gen == generation && manualNext === manual) advanceWithTransitionFallback()
                    }
                    return
                }
                val blend = if (!blendRejected && frame != null && source !is TrackBlend && next != null) {
                    if (manual == null) next.plan?.let { TrackTransition.atPosition(it, decodedMs.toLong(), durationMs) }
                    else TrackTransition.manualAt(next.mode, next.plan, decodedMs.toLong(), durationMs,
                        next.loaded.durationMs.takeIf { it > 0 } ?: next.track.durationMs, prepareSeconds)
                } else null
                if (frame == null || blend != null) {
                    if (next != null && next.mode == transitionMode && out != null && !out.lost &&
                        canTransition(manual = manual != null) && (manual == null || manual.index == next.index) &&
                        next.from === current &&
                        next.index == nextTransitionIndex() && queue.getOrNull(next.index) === next.track && gen == generation) {
                        val cue = blend?.entryMs ?: 0L
                        val incoming = runCatching {
                            TrackBlend.openIncoming(next.loaded.bytes, cue) { gen == generation }
                        }.getOrNull()
                        val inputHead = incoming?.first
                        val inputDecoder = incoming?.decoder
                        var decoder = inputDecoder
                        var head = inputHead
                        if (inputDecoder != null && inputHead != null &&
                            (inputHead.rate != outputRate || inputHead.channels != outputChannels)) {
                            head = runCatching {
                                val converted = PcmAdapter(inputDecoder, inputHead, outputRate, outputChannels)
                                decoder = converted
                                converted.read()
                            }.getOrNull()
                        }
                        if (incoming != null && decoder != null && head != null &&
                            (frame == null || (frame.rate == outputRate && frame.channels == outputChannels))) {
                            if (frame == null) {
                                try { source?.close() } catch (_: Exception) {}
                                source = decoder
                                pending = head
                            } else if (blend != null) {
                                source = TrackBlend(source!!, frame, decoder, head,
                                    blend.overlapMs, trackGain, next.loaded.gain, blend.bassExchange, blend.speed)
                            }
                            handoff = next
                            handoffPlan = blend
                            boundaryMicros = writtenFrames * 1_000_000L / outputRate
                            decodedMs = incoming.skippedMs
                            incomingStartMs = decodedMs.toLong()
                            DangoLogger.info("Dango Music transition queued: mode={} actual={} overlapMs={} incomingMs={} speed={} manual={} converted={}",
                                next.mode, blend?.mode ?: TrackTransitionMode.GAPLESS, blend?.overlapMs ?: 0L,
                                incomingStartMs, blend?.speed ?: 1.0, manual != null, inputHead !== head)
                            lowPass = 0f
                            avgEnergy = 0f
                            lastBeatMs = -1000f
                            prepared = null
                            synchronized(this) { if (gen == generation) preparedForSeek = null }
                            requested = false
                            blendRejected = false
                            continue
                        }
                        try { decoder?.close() } catch (_: Exception) {}
                        blendRejected = true
                    }
                    if (frame == null) {
                        if (prepareMode != TrackTransitionMode.OFF && nextTransitionIndex() >= 0 && gen == generation) {
                            DangoLogger.info("Dango Music transition: mode={} regular cut (prepared={}, pending={})",
                                prepareMode, prepared != null, prefetch != null)
                        }
                        finished = true
                        break
                    }
                }
                if (out == null) {
                    val format = AudioFormat(frame.rate.toFloat(), 16, frame.channels, true, false)
                    out = openOutput(format, frame.rate * frame.channels * 2 / 4)   // 约 0.25 秒缓冲
                    outputRate = frame.rate
                    outputChannels = frame.channels
                    line = out
                    if (state == State.PAUSED) {
                        out.stop()
                    } else {
                        out.start()
                        state = State.PLAYING
                    }
                }
                // 暂停：不再往声卡写数据，原地等待。
                // （Java Sound 在输出停止时被写数据会自动重新开始或直接返回，
                //   不这样等的话会一口气把整首歌"读完"，然后误以为放完了跳下一首）
                while (state == State.PAUSED && gen == generation) {
                    if (out.isRunning) out.stop()   // 写数据时可能被自动重新开始，确保停住
                    Thread.sleep(15)
                }
                if (gen != generation) break
                // 只准备队列里紧接着的一首，下载慢时到 EOF 仍照常切歌。
                if (!requested && handoff == null && canTransition(manual = manual != null) &&
                    (prepareMode != TrackTransitionMode.GAPLESS || durationMs - decodedMs <= 30_000f)) {
                    val nextIndex = nextTransitionIndex()
                    val from = current
                    val track = queue.getOrNull(nextIndex)
                    if (from != null && track != null) {
                        val mode = prepareMode
                        val seconds = prepareSeconds
                        val matchTempo = prepareTempo
                        val outgoing = audio ?: bytes
                        val outgoingMs = durationMs
                        requested = true
                        prefetch = preloader.submit<PreparedNext> {
                            val active = { gen == generation && !Thread.currentThread().isInterrupted }
                            if (!active()) throw CancellationException()
                            val loaded = loadTrack(track, active)
                            val plan = runCatching {
                                TrackTransition.plan(mode, outgoing, loaded.bytes, outgoingMs,
                                    loaded.durationMs.takeIf { it > 0 } ?: track.durationMs, active, seconds, matchTempo)
                            }.getOrNull()
                            PreparedNext(from, nextIndex, track, loaded, mode, seconds, matchTempo, plan)
                        }
                    }
                }
                if (volume != appliedVolume) {
                    out.setVolume(volume)
                    appliedVolume = volume
                }
                val len = frame.length
                val pcm = frame.samples

                // 鼓点：低频（底鼓）能量比最近一秒的平均值突然高出很多
                val frameMs = frame.durationMs
                val ch = frame.channels.coerceAtLeast(1)
                var energy = 0f
                var k = 0
                while (k + ch - 1 < len) {
                    var mono = 0f
                    for (c in 0 until ch) mono += pcm[k + c].toFloat()
                    mono /= ch * 32768f
                    lowPass += (mono - lowPass) * 0.04f
                    energy += lowPass * lowPass
                    k += ch
                }
                energy /= (len / ch).coerceAtLeast(1)
                if (handoff == null && avgEnergy > 0f && energy > avgEnergy * 1.7f && energy > 2e-4f && decodedMs - lastBeatMs > 170f) {
                    val strength = ((energy / avgEnergy - 1.7f) / 2f + 0.45f).coerceIn(0f, 1f)
                    synchronized(beats) {
                        beats.addLast(Beat(decodedMs.toLong(), strength))
                        while (beats.size > 48) beats.removeFirst()
                    }
                    lastBeatMs = decodedMs
                }
                avgEnergy = if (avgEnergy == 0f) energy else avgEnergy + (energy - avgEnergy) * (frameMs / 1000f).coerceAtMost(1f)
                decodedMs += frameMs
                // 音量均衡、环境音效（水下变闷、洞穴回声……）在鼓点检测之后加，不影响节奏判断
                val gain = if (source is TrackBlend) 1f else handoff?.loaded?.gain ?: trackGain
                if (gain != 1f) for (idx in 0 until len) pcm[idx] = (pcm[idx] * gain).toInt().coerceIn(-32768, 32767).toShort()
                EnvironmentAudio.process(pcm, len, ch, frame.rate, frameMs)
                val data = ByteArray(len * 2)
                for (k in 0 until len) {
                    val v = pcm[k].toInt()
                    data[k * 2] = (v and 0xFF).toByte()
                    data[k * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                try {
                    writePcm(out, data)
                } catch (e: Throwable) {
                    if (!out.lost || gen != generation) throw e
                    // 游戏重启了声音系统：换一个新的输出，从这一块接着放（缓冲里没放完的零点几秒丢掉）
                    val reopened = reopenOutput(gen, out, frame.rate, frame.channels) ?: throw e
                    out = reopened
                    outputRate = frame.rate
                    outputChannels = frame.channels
                    writtenFrames = 0L
                    val frameStartMs = (decodedMs - frameMs).toLong()
                    if (handoff == null) {
                        seekBaseMs = frameStartMs
                    } else {
                        boundaryMicros = 0L
                        incomingStartMs = frameStartMs
                    }
                    appliedVolume = -1f
                    if (reopened.lost) throw e
                    reopened.setVolume(volume)
                    appliedVolume = volume
                    writePcm(reopened, data)
                }
                if (gen != generation) break
                writtenFrames += len / outputChannels
                // 写进缓冲的 PCM 还没出声，等输出时钟到达两首歌的交界再更新歌词。
                if (handoff != null) {
                    val position = out.positionMicros
                    if (position >= boundaryMicros) publishHandoff(position)
                }
            }
            if (finished && out == null && gen == generation) {
                finished = false
                fail(gen, "No decodable audio was found.")
            }
            if (finished && gen == generation) {
                // 最后一点正在放的时候声音系统重启了：就当放完了
                try { out?.drain() } catch (e: Throwable) { if (out?.lost != true) throw e }
                if (handoff != null && out?.lost == false) publishHandoff(out.positionMicros)
                synchronized(this) { if (gen == generation) failedTracks.clear() }
            }
        } catch (e: Throwable) {
            finished = false
            DangoLogger.warn("Music decode failed: {}", e.toString())
            if (gen == generation) {
                if (handoff != null) publishHandoff(boundaryMicros)
                fail(gen, "播放失败：" + describe(e))
            }
        } finally {
            prefetch?.cancel(true)
            try { source?.close() } catch (_: Exception) {}
            synchronized(this) { if (line === out) line = null }
            try {
                out?.stop()
                out?.close()
            } catch (_: Exception) {
            }
        }
        if (finished) synchronized(this) {
            if (gen != generation) return
            if (followMode) {
                // 跟随房主时放完就停在结尾，等房主切下一首
                pausedAtMs = durationMs
                state = State.PAUSED
            } else if (manualNext?.generation == gen) {
                advanceWithTransitionFallback()
            } else if (repeatOne) {
                // 复用已下载的音频，从头解码，不重新请求播放地址或下载。
                seek(0L)
            } else {
                advanceWithTransitionFallback()
            }
        }
    }

    private fun stopLine() {
        val l = line
        line = null
        try {
            l?.stop()
            l?.flush()
            l?.close()
        } catch (_: Exception) {
        }
    }

    const val OUTPUT_AUTO = 0
    const val OUTPUT_JAVA_SOUND = 1
    const val OUTPUT_GAME = 2

}
