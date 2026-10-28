package me.sorihiko.dango.music

import com.google.gson.JsonObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/** Native web QR flows. Requests, cookies and credentials stay isolated for each provider. */
object NativeLogin {
    @Volatile var source = MusicSource.QQ; private set
    @Volatile var state = NeteaseLogin.State.IDLE; private set
    @Volatile var qrImage: BufferedImage? = null; private set
    @Volatile var qrVersion = 0; private set
    @Volatile var message = ""; private set
    private val lock = Any()
    @Volatile private var session = 0
    @Volatile private var appChannel: QqAppChannel? = null

    fun start(provider: MusicSource) {
        require(provider != MusicSource.NETEASE)
        val current = synchronized(lock) {
            appChannel?.close(); appChannel = null
            ++session
            source = provider; state = NeteaseLogin.State.LOADING; qrImage = null; message = ""
            session
        }
        Thread({
            try {
                if (provider == MusicSource.QQ) qq(current) else kugou(current)
            } catch (error: Exception) {
                update(current) {
                    state = NeteaseLogin.State.ERROR
                    message = if (error is SourceAccessException) error.message.orEmpty() else "Login request failed. Refresh the QR code and try again."
                }
            }
        }, "Dango-${provider.key}-Login").apply { isDaemon = true; start() }
    }

    fun cancel() = synchronized(lock) {
        appChannel?.close(); appChannel = null
        ++session; qrImage = null
        if (state != NeteaseLogin.State.SUCCESS) state = NeteaseLogin.State.IDLE
    }

    private fun update(current: Int, apply: () -> Unit) = synchronized(lock) { if (current == session) apply() }
    private fun success(current: Int, account: NativeAccount) = update(current) {
        NativeAccounts.save(account)
        state = NeteaseLogin.State.SUCCESS
    }
    private fun show(current: Int, bytes: ByteArray) {
        require(bytes.size <= 1024 * 1024)
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("Invalid QR image")
        require(image.width in 32..1024 && image.height in 32..1024)
        update(current) { qrImage = image; qrVersion++; state = NeteaseLogin.State.WAITING }
    }

    private fun qq(current: Int) {
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val request = qqRequest("CreateQRCode", JsonObject().apply {
            addProperty("tmeAppID", "qqmusic")
            // These belong in param, independently of comm.ct/cv. Without them the
            // shortlink is created but the phone authorization page rejects it (104610).
            addProperty("ct", 19)
            addProperty("cv", 2201)
        })
        val data = qqData(AuthHttp.request(MusicSource.QQ, "https://u.y.qq.com/cgi-bin/musicu.fcg", cookies, request.toString()).json())
        val id = data.text("qrcodeID")
        val encoded = data.text("qrcode")
        check(id.isNotEmpty() && encoded.isNotEmpty())
        val image = Base64.getDecoder().decode(encoded.substringAfter(',', encoded))
        val deadline = System.currentTimeMillis() + 180000
        val channel = QqAppChannel()
        val attached = synchronized(lock) {
            if (current == session) { appChannel = channel; true } else false
        }
        if (!attached) { channel.close(); return }
        try {
            // Only reveal the QR after its confirmation listener has been acknowledged.
            channel.connect(id)
            if (current != session) return
            show(current, image)
            while (current == session && System.currentTimeMillis() < deadline) {
                val event = channel.next(deadline) ?: break
                if (current != session) return
                when (event.type) {
                    "scanned" -> update(current) { state = NeteaseLogin.State.SCANNED }
                    "timeout" -> { update(current) { state = NeteaseLogin.State.EXPIRED }; return }
                    "canceled" -> {
                        update(current) { state = NeteaseLogin.State.ERROR; message = "Login was cancelled on your phone. Refresh to retry." }
                        return
                    }
                    "loginFailed" -> throw SourceAccessException("QQ Music rejected the login. Please check your account and try again.")
                    "cookies" -> {
                        update(current) { state = NeteaseLogin.State.SCANNED; message = "Phone confirmed. Verifying desktop login..." }
                        val payload = com.google.gson.JsonParser().parse(String(event.payload, Charsets.UTF_8)).asJsonObject
                        val account = QqAppSession().complete(payload, id) { current == session }
                        success(current, account)
                        return
                    }
                }
            }
            update(current) { state = NeteaseLogin.State.EXPIRED }
        } finally {
            channel.close()
            synchronized(lock) { if (appChannel === channel) appChannel = null }
        }
    }

    private fun qqRequest(method: String, params: JsonObject, loginType: Int? = null) = JsonObject().apply {
        add("comm", JsonObject().apply {
            addProperty("ct", 23); addProperty("cv", 0)
            if (loginType != null) addProperty("tmeLoginType", loginType)
        })
        add("req_0", JsonObject().apply {
            addProperty("module", "music.login.LoginServer"); addProperty("method", method); add("param", params)
        })
    }

    private fun qqData(response: JsonObject): JsonObject {
        val result = response.getAsJsonObject("req_0") ?: error("Missing QQ Music response")
        val code = if (response.number("code") != 0L) response.number("code") else result.number("code")
        when (code) {
            0L -> return result.getAsJsonObject("data") ?: error("Missing QQ Music data")
            20279L -> throw SourceAccessException("QQ Music device limit reached. Remove an unused device in the app and retry.")
            104604L -> throw SourceAccessException("Too many login attempts. Please try again later.")
            1000L, 104400L, 104401L -> throw SourceAccessException("Login expired. Refresh the QR code.")
            else -> throw SourceAccessException("QQ Music rejected the login. Please check your account and try again.")
        }
    }

    private fun kugou(current: Int) {
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val device = UUID.randomUUID().toString().replace("-", "")
        val root = KugouWeb.get("https://login-user.kugou.com/v2/qrcode", mapOf("clientver" to "1000", "type" to "1", "plat" to "4",
            "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=1014&"), device, cookies)
        check(root.number("status") == 1L)
        val data = root.getAsJsonObject("data") ?: error("No Kugou QR")
        val key = data.text("qrcode")
        check(key.isNotEmpty())
        val image = data.text("qrcode_img")
        check(image.startsWith("data:image/png;base64,"))
        show(current, Base64.getDecoder().decode(image.substringAfter(',')))
        val started = System.currentTimeMillis()
        while (current == session && System.currentTimeMillis() - started < 180_000) {
            Thread.sleep(2000)
            if (current != session) return
            val poll = KugouWeb.get("https://login-user.kugou.com/v2/get_userinfo_qrcode",
                mapOf("clientver" to "1000", "plat" to "4", "qrcode" to key), device, cookies)
            check(poll.number("status") == 1L)
            val state = poll.getAsJsonObject("data") ?: error("No Kugou QR status")
            when (state.number("status")) {
                1L -> update(current) { this.state = NeteaseLogin.State.WAITING }
                2L -> update(current) { this.state = NeteaseLogin.State.SCANNED }
                4L -> {
                    val info = state.getAsJsonObject("userinfo") ?: state.getAsJsonObject("user_info") ?: state
                    val id = info.text("userid").ifEmpty { state.text("userid") }
                    val token = info.text("token").ifEmpty { state.text("token") }
                    check(id.isNotEmpty() && token.isNotEmpty())
                    if (current != session) return
                    val exchanged = KugouWeb.tokenLogin(id, token, device, cookies)
                    val accountData = exchanged.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    val webToken = KugouWeb.cookiePart(cookies, "t").ifEmpty { accountData?.text("token").orEmpty() }
                    check(webToken.isNotEmpty())
                    val webId = KugouWeb.cookiePart(cookies, "KugooID").ifEmpty { id }
                    val nickname = accountData?.text("nickname").orEmpty().ifEmpty { info.text("nickname").ifEmpty { state.text("nickname") } }
                    val avatar = KugouCatalog.image(accountData?.text("pic").orEmpty().ifEmpty { info.text("pic").ifEmpty { info.text("picurl") } })
                    success(current, NativeAccount(MusicSource.KUGOU, webId, nickname, webToken, device, cookies, avatarUrl = avatar))
                    return
                }
                else -> { update(current) { this.state = NeteaseLogin.State.EXPIRED }; return }
            }
        }
        update(current) { state = NeteaseLogin.State.EXPIRED }
    }
}
