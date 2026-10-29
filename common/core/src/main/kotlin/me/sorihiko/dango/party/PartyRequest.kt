package me.sorihiko.dango.party

import com.google.gson.JsonObject
import me.sorihiko.dango.music.NeteaseTrack

/**
 * 一起听歌：成员的操作请求（rooms/{房间码}/req），房主的游戏收到后自动执行，再照常同步状态给所有人。
 * 新请求直接覆盖旧的，房主按 [id] 判断是不是处理过。
 */
class PartyRequest(
    val id: String,
    val by: String,
    val name: String,
    val op: String,
    /** [SEEK] / [PAUSE] / [RESUME]：成员发请求那一刻的进度 */
    val pos: Long,
    /** 服务器时间，房主用来补上请求在路上花的时间 */
    val at: Long,
    /** [PLAY]：要放的歌 */
    val track: NeteaseTrack?,
) {
    companion object {
        const val PLAY = "play"
        const val NEXT = "next"
        const val PREV = "prev"
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val SEEK = "seek"

        /** 太旧的请求（比如房主刚重连、读到之前留下的）不再执行 */
        const val MAX_AGE_MS = 15_000L

        private val OPS = setOf(PLAY, NEXT, PREV, PAUSE, RESUME, SEEK)

        fun write(r: PartyRequest, serverTimestamp: com.google.gson.JsonElement): JsonObject =
            (r.track?.let { PartyTrackCodec.write(it) } ?: JsonObject()).apply {
                addProperty("id", r.id)
                addProperty("by", r.by)
                addProperty("byName", r.name)
                addProperty("op", r.op)
                addProperty("pos", r.pos)
                add("at", serverTimestamp)
            }

        fun read(o: JsonObject?): PartyRequest? {
            if (o == null) return null
            return try {
                val op = o.get("op")?.asString ?: return null
                if (op !in OPS) return null
                val id = o.get("id")?.asString ?: return null
                val track = if (op == PLAY) PartyTrackCodec.read(o) ?: return null else null
                PartyRequest(
                    id.take(64), o.get("by")?.asString ?: return null, (o.get("byName")?.asString ?: "?").take(64), op,
                    (o.get("pos")?.asLong ?: 0L).coerceAtLeast(0L), o.get("at")?.asLong ?: 0L, track,
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 房主该不该执行这条请求：没处理过、是房间里的成员发的（不是自己）、不太旧、房主开着"允许成员控制"。
         */
        fun accept(r: PartyRequest?, lastId: String, hostUid: String, members: Collection<String>, memberControl: Boolean, serverNow: Long): Boolean =
            r != null && memberControl && r.id != lastId && r.by != hostUid && r.by in members &&
                serverNow - r.at < MAX_AGE_MS
    }
}
