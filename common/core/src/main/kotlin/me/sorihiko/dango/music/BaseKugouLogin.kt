package me.sorihiko.dango.music

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 酷狗音乐 / 酷狗概念版 扫码登录基类。
 *
 * 登录流程（基于官方 H5 接口及反代协议）：
 *   1. 请求 https://login-user.kugou.com/v2/qrcode 获取二维码唯一 key 与 qrcode_img / url
 *   2. 本地渲染或直接解析链接为二维码图片展示
 *   3. 轮询 https://login-user.kugou.com/v2/get_userinfo_qrcode 检测扫码状态：
 *      - status = 1: 等待扫码 (WAITING)
 *      - status = 2: 待确认 (SCANNED)
 *      - status = 0: 二维码过期 (EXPIRED)
 *      - status = 4: 授权登录成功 (SUCCESS)，返回 token 与 userid
 */
abstract class BaseKugouLogin(
    val appid: Int,
    val clientver: Int,
    override val platformName: String,
    private val cookieFileName: String,
) : AccountLogin() {

    override val scanPrompt: String
        get() = "打开手机$platformName App 扫码"

    private val cookieFile: File
        get() = DangoMusic.instance.clientDir.resolve(cookieFileName).toFile()

    @Volatile var token: String = ""
        private set
    @Volatile var userId: Long = 0L
        private set

    init {
        loadSavedCredentials()
    }

    private fun loadSavedCredentials() {
        try {
            val f = cookieFile
            if (f.isFile) {
                val lines = f.readLines(StandardCharsets.UTF_8)
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("token=")) token = trimmed.substringAfter("token=")
                    if (trimmed.startsWith("userid=")) userId = trimmed.substringAfter("userid=").toLongOrNull() ?: 0L
                }
            }
        } catch (_: Exception) {}
    }

    fun saveCredentials(token: String, userId: Long) {
        this.token = token
        this.userId = userId
        try {
            val f = cookieFile
            f.parentFile.mkdirs()
            f.writeText("token=$token\nuserid=$userId\n", StandardCharsets.UTF_8)
        } catch (e: Exception) {
            DangoLogger.warn("Save $platformName credentials failed: {}", e.toString())
        }
    }

    fun logout() {
        token = ""
        userId = 0L
        try {
            val f = cookieFile
            if (f.exists()) f.delete()
        } catch (_: Exception) {}
    }

    override fun run(mySession: Int) {
        try {
            val clienttime = (System.currentTimeMillis() / 1000).toString()
            val qrParams = linkedMapOf(
                "appid" to "1001",
                "clienttime" to clienttime,
                "clientver" to clientver.toString(),
                "mid" to "0",
                "plat" to "4",
                "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=$appid&",
                "srcappid" to "2919",
                "type" to "1",
                "uuid" to "0",
            )
            val qrUrl = "https://login-user.kugou.com/v2/qrcode" + signAndBuildQuery(qrParams)
            val qrResp = httpGet(qrUrl)
            if (mySession != session) return

            val qrJson = qrResp?.let { JsonParser().parse(it).asJsonObject }
            val qrData = qrJson?.getAsJsonObject("data")
            val key = qrData?.get("qrcode")?.asString

            if (key.isNullOrEmpty()) {
                if (mySession == session) state = State.ERROR
                return
            }

            // 使用官方二维码链接生成二维码图片
            val qrContent = "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=$key"
            qrImage = generateQrImage(qrContent, 256)
            qrVersion++
            state = State.WAITING

            // 轮询状态
            while (mySession == session) {
                Thread.sleep(2000)
                if (mySession != session) return

                val checkTime = (System.currentTimeMillis() / 1000).toString()
                val checkParams = linkedMapOf(
                    "appid" to appid.toString(),
                    "clienttime" to checkTime,
                    "clientver" to clientver.toString(),
                    "mid" to "0",
                    "plat" to "4",
                    "qrcode" to key,
                    "srcappid" to "2919",
                    "uuid" to "0",
                )
                val checkUrl = "https://login-user.kugou.com/v2/get_userinfo_qrcode" + signAndBuildQuery(checkParams)
                val checkResp = httpGet(checkUrl) ?: continue
                val respObj = JsonParser().parse(checkResp).asJsonObject
                val status = respObj.getAsJsonObject("data")?.get("status")?.asInt ?: continue

                when (status) {
                    1 -> state = State.WAITING
                    2 -> state = State.SCANNED
                    0 -> {
                        state = State.EXPIRED
                        return
                    }
                    4 -> {
                        val dataObj = respObj.getAsJsonObject("data")
                        val gotToken = dataObj.get("token")?.asString ?: ""
                        val gotUserId = dataObj.get("userid")?.asLong ?: 0L
                        if (gotToken.isNotEmpty()) {
                            saveCredentials(gotToken, gotUserId)
                            state = State.SUCCESS
                        } else {
                            state = State.ERROR
                        }
                        return
                    }
                }
            }
        } catch (e: Exception) {
            DangoLogger.warn("$platformName QR login failed: {}", e.toString())
            if (mySession == session) state = State.ERROR
        }
    }

    companion object {
        private const val WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"

        /**
         * 酷狗 Web / H5 签名算法：按 key 排序拼接为 key=value 再加上两端 Salt 进行 md5 计算
         */
        fun signAndBuildQuery(params: Map<String, String>): String {
            val sorted = params.toSortedMap()
            val paramStr = sorted.entries.joinToString("") { "${it.key}=${it.value}" }
            val raw = "$WEB_SALT$paramStr$WEB_SALT"
            val sign = md5(raw)

            val query = sorted.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            } + "&signature=$sign"
            return "?$query"
        }

        private fun md5(str: String): String {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(str.toByteArray(StandardCharsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun httpGet(urlString: String): String? {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
                )
                if (conn.responseCode !in 200..299) return null
                return conn.inputStream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        }
    }
}
