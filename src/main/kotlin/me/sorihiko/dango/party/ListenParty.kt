package me.sorihiko.dango.party

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.mc
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.NeteaseTrack

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 一起听歌（通过 Firebase 实时数据库同步）：
 *   房主：开房间 → 得到房间码；自己正常放歌，切歌 / 拖进度 / 暂停时把状态写到数据库
 *   成员：输入房间码加入 → 监听房间，自动放同一首歌、跳到同一位置、跟着暂停
 * 每个人都是自己从网易云拉歌，只同步"哪首歌、第几秒、在不在放"。
 *
 * 数据结构：rooms/{房间码} = { host, hostName, members/{uid} = {name, lastSeen}, state = {...} }
 */
object ListenParty {

    enum class Role { NONE, HOST, MEMBER }

    class Member(val uid: String, val name: String, val lastSeen: Long)

    private class RemoteState(
        val trackId: Long, val name: String, val artists: List<String>, val cover: String, val duration: Long,
        val pos: Long, val playing: Boolean, val at: Long,
    )

    @Volatile var role = Role.NONE
        private set
    @Volatile var code = ""
        private set
    @Volatile var hostName = ""
        private set
    @Volatile var hostUid = ""
        private set
    @Volatile var members: List<Member> = emptyList()
        private set
    /** 给界面看的状态 / 错误信息 */
    @Volatile var status = ""
        private set
    @Volatile var busy = false
        private set

    @Volatile private var remote: RemoteState? = null
    private var stopListen: (() -> Unit)? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Dango-Party").apply { isDaemon = true } }
    private val refreshPending = AtomicBoolean(false)
    private val rng = SecureRandom()

    // 房主：上一次发出去的状态
    private var sentId = 0L
    private var sentPlaying = false
    private var sentPos = 0L
    private var sentAt = 0L
    // 成员：已经请求加载的歌、上次纠正的时间
    @Volatile private var requestedId = 0L
    private var lastCorrection = 0L
    private var lastHeartbeat = 0L

    init {
        Thread({
            while (true) {
                try {
                    Thread.sleep(300)
                    when (role) {
                        Role.HOST -> hostTick()
                        Role.MEMBER -> followTick()
                        Role.NONE -> {}
                    }
                    if (role != Role.NONE && System.currentTimeMillis() - lastHeartbeat > 20_000) heartbeat()
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    DangoLogger.warn("Listen party tick failed: {}", e.toString())
                }
            }
        }, "Dango-PartyTick").apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread { if (role != Role.NONE) leaveNow() })
    }

    private fun myName(): String = try { mc.session.username } catch (_: Exception) { "Player" }

    private fun sv(): JsonElement = JsonParser().parse("""{".sv":"timestamp"}""")

    // ---------------------------------------------------------------- 开房 / 加入 / 离开

    fun create() {
        if (busy || role != Role.NONE) return
        busy = true
        status = "正在创建房间…"
        worker.execute {
            try {
                if (!Firebase.configured) return@execute fail("还没配置 Firebase（firebase.properties）")
                if (!Firebase.ensureAuth()) return@execute fail("连不上 Firebase（" + Firebase.lastStatus + "）")
                Firebase.syncClock()
                val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
                repeat(6) {
                    val c = (1..5).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
                    val room = JsonObject().apply {
                        addProperty("host", Firebase.uid)
                        addProperty("hostName", myName())
                        add("createdAt", sv())
                        add("members", JsonObject().apply {
                            add(Firebase.uid, JsonObject().apply {
                                addProperty("name", myName())
                                add("lastSeen", sv())
                            })
                        })
                    }
                    if (Firebase.put("rooms/$c", room) != null) {
                        code = c
                        hostUid = Firebase.uid
                        hostName = myName()
                        role = Role.HOST
                        sentId = 0L                      // 马上把当前播放状态发出去
                        lastHeartbeat = System.currentTimeMillis()
                        startListening()
                        status = ""
                        return@execute
                    }
                }
                fail("创建房间失败（" + Firebase.lastStatus + "）")
            } finally {
                busy = false
            }
        }
    }

    fun join(input: String) {
        val c = input.trim().uppercase()
        if (busy || role != Role.NONE || c.isEmpty()) return
        busy = true
        status = "正在加入 $c …"
        worker.execute {
            try {
                if (!Firebase.configured) return@execute fail("还没配置 Firebase（firebase.properties）")
                if (!Firebase.ensureAuth()) return@execute fail("连不上 Firebase（" + Firebase.lastStatus + "）")
                Firebase.syncClock()
                val room = Firebase.get("rooms/$c")
                if (room == null || !room.isJsonObject) return@execute fail("房间 $c 不存在")
                val me = JsonObject().apply {
                    addProperty("name", myName())
                    add("lastSeen", sv())
                }
                if (Firebase.put("rooms/$c/members/${Firebase.uid}", me) == null) {
                    return@execute fail("加入失败（" + Firebase.lastStatus + "）")
                }
                code = c
                role = Role.MEMBER
                requestedId = 0L
                MusicPlayer.followMode = true
                lastHeartbeat = System.currentTimeMillis()
                apply(room.asJsonObject)
                startListening()
                status = ""
            } finally {
                busy = false
            }
        }
    }

    fun leave() {
        if (role == Role.NONE) return
        worker.execute { leaveNow() }
    }

    private fun leaveNow() {
        val c = code
        val wasHost = role == Role.HOST
        stopListen?.invoke()
        stopListen = null
        role = Role.NONE
        code = ""
        members = emptyList()
        remote = null
        MusicPlayer.followMode = false
        try {
            if (wasHost) Firebase.delete("rooms/$c") else Firebase.delete("rooms/$c/members/${Firebase.uid}")
        } catch (_: Exception) {
        }
    }

    /** 成员：重新和房主对齐（自己点了别的歌之后想回来时用） */
    fun resync() {
        requestedId = 0L
        lastCorrection = 0L
    }

    private fun fail(msg: String) {
        status = msg
        busy = false
    }

    // ---------------------------------------------------------------- 房间变化

    private fun startListening() {
        stopListen?.invoke()
        val c = code
        stopListen = Firebase.listen("rooms/$c") {
            // 变化可能一下来好几条，合并成一次刷新
            if (refreshPending.compareAndSet(false, true)) {
                worker.execute {
                    refreshPending.set(false)
                    if (code != c || role == Role.NONE) return@execute
                    val room = Firebase.get("rooms/$c")
                    if (room == null || !room.isJsonObject) {
                        if (Firebase.lastStatus in 200..299 && role == Role.MEMBER) {
                            leaveNow()
                            status = "房主关闭了房间"
                        }
                        return@execute
                    }
                    apply(room.asJsonObject)
                }
            }
        }
    }

    private fun apply(room: JsonObject) {
        hostUid = room.get("host")?.asString ?: ""
        hostName = room.get("hostName")?.asString ?: ""
        val now = Firebase.serverNow()
        members = (room.get("members") as? JsonObject)?.entrySet()?.mapNotNull { (uid, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val seen = o.get("lastSeen")?.let { if (it.isJsonPrimitive) it.asLong else 0L } ?: 0L
            if (now - seen > 90_000) null else Member(uid, o.get("name")?.asString ?: "?", seen)
        }?.sortedBy { if (it.uid == hostUid) 0 else 1 } ?: emptyList()

        val st = room.get("state") as? JsonObject
        remote = if (st == null) null else try {
            RemoteState(
                trackId = st.get("trackId").asLong,
                name = st.get("name")?.asString ?: "",
                artists = (st.get("artists")?.asString ?: "").split(" / ").filter { it.isNotEmpty() },
                cover = st.get("cover")?.asString ?: "",
                duration = st.get("duration")?.asLong ?: 0L,
                pos = st.get("pos")?.asLong ?: 0L,
                playing = st.get("playing")?.asBoolean ?: false,
                at = st.get("at")?.asLong ?: now,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun heartbeat() {
        lastHeartbeat = System.currentTimeMillis()
        val c = code
        if (c.isEmpty()) return
        Firebase.patch("rooms/$c/members/${Firebase.uid}", JsonObject().apply { add("lastSeen", sv()) })
    }

    // ---------------------------------------------------------------- 房主：把自己的播放状态发出去

    private fun hostTick() {
        val cur = MusicPlayer.current ?: return
        val st = MusicPlayer.state
        val now = System.currentTimeMillis()
        if (st == MusicPlayer.State.LOADING || st == MusicPlayer.State.ERROR) {
            // 切歌了先告诉大家开始加载这首（暂停状态），真正开始放时再发一次
            if (cur.id != sentId) send(cur, 0L, false)
            return
        }
        val playing = st == MusicPlayer.State.PLAYING
        val pos = MusicPlayer.positionMs
        val predicted = sentPos + if (sentPlaying) now - sentAt else 0L
        val changed = cur.id != sentId || playing != sentPlaying || abs(pos - predicted) > 1500 || now - sentAt > 15_000
        if (changed) send(cur, pos, playing)
    }

    private fun send(t: NeteaseTrack, pos: Long, playing: Boolean) {
        val now = System.currentTimeMillis()
        sentId = t.id
        sentPlaying = playing
        sentPos = pos
        sentAt = now
        val o = JsonObject().apply {
            addProperty("trackId", t.id)
            addProperty("name", t.name)
            addProperty("artists", t.artists.joinToString(" / "))
            addProperty("cover", t.coverUrl)
            addProperty("duration", t.durationMs)
            addProperty("pos", pos)
            addProperty("playing", playing)
            addProperty("at", Firebase.serverNow())
        }
        Firebase.put("rooms/$code/state", o)
    }

    // ---------------------------------------------------------------- 成员：跟着房主放

    private fun expectedPos(s: RemoteState): Long =
        s.pos + if (s.playing) (Firebase.serverNow() - s.at).coerceAtLeast(0L) else 0L

    private fun followTick() {
        val s = remote ?: return
        val cur = MusicPlayer.current
        if (cur?.id != s.trackId) {
            if (requestedId != s.trackId) {
                requestedId = s.trackId
                val track = NeteaseTrack(s.trackId, s.name, s.artists, s.duration, s.cover)
                MusicPlayer.playTrackAt(track, { expectedPos(s) }, !s.playing)
                lastCorrection = System.currentTimeMillis()
            }
            return
        }
        val st = MusicPlayer.state
        if (st == MusicPlayer.State.LOADING || st == MusicPlayer.State.ERROR) return
        val now = System.currentTimeMillis()
        if (now - lastCorrection < 2000) return

        val exp = expectedPos(s)
        val localPlaying = st == MusicPlayer.State.PLAYING
        val drift = abs(MusicPlayer.positionMs - exp)
        if (s.playing != localPlaying || (s.playing && drift > 700) || (!s.playing && drift > 1500)) {
            MusicPlayer.syncTo(exp, s.playing)
            lastCorrection = now
        }
    }
}
