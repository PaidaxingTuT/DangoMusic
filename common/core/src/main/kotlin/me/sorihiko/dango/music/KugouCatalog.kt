package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap

/** User-local cloud IDs and public rank IDs are separate namespaces, like the QQ catalog. */
internal class KugouCatalog(
    private val source: MusicSource = MusicSource.KUGOU,
    private val call: (String, Map<String,String>, JsonObject?, NativeAccount?) -> JsonObject =
        if (source == MusicSource.KUGOU_CONCEPT) KugouConcept::request else KugouGateway::request,
    private val homepage: () -> String = {
        SourceHttp.bytes("https://www.kugou.com/", "https://www.kugou.com/",4*1024*1024)
            ?.let { String(it,Charsets.UTF_8) } ?: throw SourceAccessException("Kugou recommendations are unavailable. Please retry.")
    },
) {
    private class Ref(val kind: String, val native: Long, val owner: String = "")
    private val refs = ConcurrentHashMap<Long, Ref>()
    private val recommendations = ConcurrentHashMap<Long,List<NeteaseTrack>>()

    fun playlists(): List<NeteasePlaylist> {
        val account = NativeAccounts.get(source) ?: return emptyList()
        val rows = mutableListOf<JsonObject>()
        val seen = HashSet<String>()
        var received = 0
        for (page in 1..200) {
            val data = call("/cloudlist.service/v8/get_all_list", mapOf("plat" to "1"), JsonObject().apply {
                addProperty("userid",account.userId.toLong()); addProperty("token",account.token)
                addProperty("total_ver",0); addProperty("type",2); addProperty("page",page); addProperty("pagesize",50)
            }, account)
            val list = data.array("info")
            if (list.size() == 0) break
            check(seen.add(list.toString())) { "Kugou repeated a library page. Please retry." }
            rows += list.filter { it.isJsonObject }.map { it.asJsonObject }.filter { it.number("is_del") != 1L }
            received += list.size()
            val total = data.number("total").takeIf { it > 0 } ?: data.number("list_count") + data.number("collect_count")
            if ((total > 0 && received >= total) || (total <= 0 && list.size() < 50)) break
            if (page == 200) throw SourceAccessException("Kugou returned too many library pages. Please retry.")
        }
        return rows.distinctBy { it.number("listid") }.sortedWith(compareBy(::playlistPinOrder).thenByDescending(::playlistAddedAt)).mapNotNull { row ->
            val local = row.number("listid").takeIf { it > 0 } ?: return@mapNotNull null
            val id = MusicSources.stableId(source,"user:${account.userId}:$local")
            refs[id] = Ref("personal",local,account.userId)
            val count = row.number("count").toInt()
            val serverCover = playlistCover(row)
            val autoLikedCover = count > 0 && row.text("is_custom_pic") != "1" &&
                (row.text("name") in listOf("我喜欢", "我喜欢的音乐") || row.number("is_def") == 1L)
            val cover = when {
                autoLikedCover -> runCatching { tracks(id).firstOrNull()?.coverUrl.orEmpty() }.getOrDefault("").ifEmpty { serverCover }
                serverCover.isEmpty() && count > 0 -> runCatching {
                    val data = cloudTrackPage(local, account, 1, 10)
                    data.array("info").mapNotNull { item ->
                        if (item.isJsonObject) KugouMusicSource.parseTrack(item.asJsonObject, source) else null
                    }.firstOrNull { it.coverUrl.isNotEmpty() }?.coverUrl.orEmpty()
                }.getOrDefault("")
                else -> serverCover
            }
            NeteasePlaylist(id,cleanSourceText(row.text("name")),count,cover,
                owned = row.text("list_create_userid").toLongOrNull()?.let { it.toString() == account.userId } ?: (row.number("type") != 1L))
        }
    }

    fun charts(): List<NeteasePlaylist> {
        val data = call("/ocean/v6/rank/list",mapOf("plat" to "2","withsong" to "1","parentid" to "0"),null,null)
        return data.array("info").mapNotNull { item ->
            val row = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val native = row.number("rankid").takeIf { it > 0 } ?: return@mapNotNull null
            val id = MusicSources.stableId(source,"rank:$native")
            refs[id] = Ref("rank",native)
            NeteasePlaylist(id,cleanSourceText(row.text("rankname")),row.number("count").toInt(),image(row.text("imgurl")),false)
        }.distinctBy { it.id }
    }

    /** Official homepage's new releases are public curated recommendations, not private daily picks. */
    fun recommend(): List<NeteasePlaylist> {
        val document = homepage()
        val section = Regex("<!--\\s*新歌首发新\\s*-->([\\s\\S]*?)(?=<!--|$)").find(document)?.groupValues?.get(1)
            ?: throw SourceAccessException("Kugou recommendations are unavailable. Please retry.")
        val tracks = LinkedHashMap<String,NeteaseTrack>()
        for (match in Regex("dataobj=['\"]([^'\"]*)['\"]").findAll(section)) {
            val value = entities(match.groupValues[1])
            val list = try { JsonParser().parse(value).takeIf { it.isJsonArray }?.asJsonArray } catch (_: Exception) { null } ?: continue
            for (item in list) if (item.isJsonObject) KugouMusicSource.parseTrack(item.asJsonObject, source)?.let { tracks[it.key] = it }
        }
        if (tracks.isEmpty()) throw SourceAccessException("Kugou recommendations are unavailable. Please retry.")
        val id = MusicSources.stableId(source,"homepage:new-releases")
        refs[id] = Ref("home",0)
        recommendations[id] = tracks.values.toList()
        val cover = Regex("_src=['\"]([^'\"]+)['\"]").find(section)?.groupValues?.get(1).orEmpty()
        return listOf(NeteasePlaylist(id,"酷狗新歌首发",tracks.size,image(cover),false))
    }

    /** 专属推荐是公开歌单，打开时必须走 specialid 接口，不能当成用户云歌单。 */
    fun specialRecommend(): List<NeteasePlaylist> {
        val now = System.currentTimeMillis() / 1000
        val concept = source == MusicSource.KUGOU_CONCEPT
        val data = call("/v2/special_recommend", emptyMap(), JsonObject().apply {
            addProperty("appid", if (concept) 3116 else 1005); addProperty("mid", "1")
            addProperty("clientver", if (concept) 11440 else 20489)
            addProperty("platform", "android"); addProperty("clienttime", now); addProperty("userid", 0)
            addProperty("module_id", 1); addProperty("page", 1); addProperty("pagesize", 30)
            addProperty("key", if (concept) KugouConcept.recommendationKey(now) else KugouGateway.recommendationKey(now))
            add("special_recommend", JsonObject().apply {
                addProperty("withtag", 1); addProperty("withsong", 0); addProperty("sort", 1); addProperty("ugc", 1)
                addProperty("is_selected", 0); addProperty("withrecommend", 1); addProperty("area_code", 1); addProperty("categoryid", 0)
            })
            addProperty("req_multi", 1); addProperty("retrun_min", 5); addProperty("return_special_falg", 1)
        }, null)
        return data.array("special_list").mapNotNull { item ->
            val row = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val native = row.number("specialid").takeIf { it > 0 } ?: return@mapNotNull null
            val id = MusicSources.stableId(source,"special:$native")
            refs[id] = Ref("special",native)
            NeteasePlaylist(id,cleanSourceText(row.text("specialname")),
                (row.number("songcount").takeIf { it > 0 } ?: row.number("song_count").takeIf { it > 0 } ?: row.number("count")).toInt(),playlistCover(row),false)
        }.distinctBy { it.id }
    }

    fun cacheRecommendations(id: Long, tracks: List<NeteaseTrack>) {
        refs[id] = Ref("home", 0)
        recommendations[id] = tracks
    }

    fun tracks(id: Long): List<NeteaseTrack> {
        val ref = refs[id] ?: throw SourceAccessException("Please reopen the playlist and try again.")
        if (ref.kind == "home") return recommendations[id] ?: emptyList()
        val account = NativeAccounts.get(source)
        if (ref.kind == "personal" && account?.userId != ref.owner)
            throw SourceAccessException("Log in to Kugou to read your playlists.")
        val tracks = LinkedHashMap<String,Pair<Long,NeteaseTrack>>()
        val seen = HashSet<String>()
        var consumed = 0L
        val pageSize = if (ref.kind == "rank") 50 else 300
        for (page in 1..400) {
            val data = when (ref.kind) {
                "rank" -> call("/ocean/v6/rank/song",
                    mapOf("rankid" to ref.native.toString(),"page" to page.toString(),"pagesize" to pageSize.toString()),null,null)
                "special" -> call("/api/v3/special/song", mapOf("specialid" to ref.native.toString(),
                    "page" to page.toString(),"pagesize" to pageSize.toString(),"format" to "json"),null,null)
                else -> cloudTrackPage(ref.native, account!!, page, pageSize)
            }
            val list = data.array("info")
            if (list.size() == 0) break
            check(seen.add(list.toString())) { "Kugou repeated a library page. Please retry." }
            for (item in list) if (item.isJsonObject) {
                val row = item.asJsonObject
                KugouMusicSource.parseTrack(row, source)?.let { tracks[it.key] = trackCollectedAt(row) to it }
            }
            val total = data.number("total").takeIf { it > 0 } ?: data.number("count")
            consumed += list.size()
            TrackLoadProgress.report(tracks.size, total.toInt())
            if ((total > 0 && consumed >= total) || (total <= 0 && list.size() < pageSize)) break
            if (page == 400) throw SourceAccessException("Kugou returned too many library pages. Please retry.")
        }
        val values = tracks.values.toList()
        return (if (ref.kind == "rank") values else values.sortedByDescending { it.first }).map { it.second }
    }

    private fun cloudTrackPage(listId: Long, account: NativeAccount, page: Int, pageSize: Int): JsonObject =
        call("/cloudlist.service/v4/get_list_all_file",mapOf("plat" to "1"),JsonObject().apply {
            addProperty("userid",account.userId.toLong()); addProperty("token",account.token); addProperty("listid",listId)
            addProperty("area_code",1); addProperty("show_relate_goods",0); addProperty("allplatform",1); addProperty("show_cover",1)
            addProperty("type",0); addProperty("page",page); addProperty("pagesize",pageSize)
        },account)

    private fun playlistCover(row: JsonObject): String =
        sequenceOf("flexible_cover", "pic", "imgurl", "cover", "img", "sizable_cover")
            .map { image(row.text(it)) }.firstOrNull { it.isNotEmpty() }
            ?: image(row.obj("trans_param")?.text("union_cover").orEmpty())

    companion object {
        private fun playlistPinOrder(row: JsonObject): Int = when {
            row.text("name") in listOf("我喜欢", "我喜欢的音乐") -> 0
            row.text("name") == "默认收藏" || row.number("is_def") in listOf(1L, 2L) || row.number("is_default") == 1L -> 1
            else -> 2
        }

        private fun playlistAddedAt(row: JsonObject) = timestamp(row, "collecttime", "addtime", "add_time", "create_time")
        private fun trackCollectedAt(row: JsonObject) = timestamp(row, "collecttime", "collect_time", "addtime", "add_time")

        /** 同时兼容秒和毫秒；无效时间沉底，同一时间维持接口顺序。 */
        internal fun timestamp(row: JsonObject, vararg fields: String): Long =
            fields.asSequence().mapNotNull { row.text(it).toLongOrNull()?.takeIf { it > 0 } }
                .map { if (it < 100_000_000_000L) it * 1000 else it }.firstOrNull() ?: 0L

        internal fun image(raw: String): String {
            val template = raw.trim()
            val value = template.replace("{size}","300")
            val url = when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http://") -> "https:" + value.substring(5)
                value.startsWith("custom/") -> "https://c1.kgimg.com/" + if ("{size}" in template) value else value.replaceFirst("custom/", "custom/300/")
                value.startsWith("stdmusic/") -> "https://imge.kugou.com/" + if ("{size}" in template) value else value.replaceFirst("stdmusic/", "stdmusic/300/")
                value.startsWith("soft/collection/") -> "https://imge.kugou.com/" + if ("{size}" in template) value else value.replaceFirst("soft/collection/", "soft/collection/300/")
                else -> value
            }
            return try { val uri=java.net.URI(url); if (uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null) url else "" } catch (_: Exception) { "" }
        }
        private fun entities(value: String) = value.replace("&#34;","\"").replace("&quot;","\"").replace("&#39;","'")
            .replace("&apos;","'").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&")
    }
}
