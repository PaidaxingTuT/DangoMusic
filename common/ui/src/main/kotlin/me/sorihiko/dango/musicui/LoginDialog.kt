package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.music.AccountLogin
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.NeteaseLogin
import me.sorihiko.dango.music.MusicServices
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

/**
 * 扫码登录弹窗：盖在整个播放器上面，开着时其它地方不响应。
 * 支持复用同一 UI 组件对不同音乐平台（网易云、酷狗、酷狗概念版）进行登录与切换。
 */
internal class LoginDialog(private val screen: MusicScreen) {
    private val ui = screen.ui
    private val hits = mutableListOf<Hit>()

    companion object {
        // 关掉播放器再打开还在（扫码可能还没完成）
        private var showing = false
        private var qrLoc: Texture? = null
        private var qrUploaded = -1

        /** 当前登录的目标平台账号 */
        var currentLogin: AccountLogin = NeteaseLogin
            private set

        val availableLogins get() = MusicServices.all.map { it.login }

        fun selectLogin(login: AccountLogin) {
            if (currentLogin != login) {
                currentLogin.cancel()
                currentLogin = login
                qrUploaded = -1
                MusicServices.select(MusicServices.forLogin(login))
                updateAccentColor()
                Library.refreshAll()
                if (showing) {
                    currentLogin.start()
                }
            }
        }

        private fun updateAccentColor() {
            ACCENT_COLOR = Color.fromHex(MusicServices.current.accentColor)
        }
    }

    val visible: Boolean get() = showing

    fun open(login: AccountLogin = currentLogin) {
        showing = true
        selectLogin(login)
        currentLogin.start()
    }

    fun close() {
        showing = false
        currentLogin.cancel()
    }

    /** 新二维码生成后传成贴图（每帧调用，没变化就什么都不做） */
    fun uploadQr() {
        val img = currentLogin.qrImage ?: return
        if (currentLogin.qrVersion == qrUploaded) return
        val textures = DangoClient.gfx.textures
        qrLoc?.let { textures.delete(it) }
        qrLoc = textures.upload("dango_qr", img)
        qrUploaded = currentLogin.qrVersion
    }

    fun draw() {
        val ctx = ui.ctx
        hits.clear()
        val state = currentLogin.state
        if (state == AccountLogin.State.SUCCESS) {
            showing = false
            currentLogin.cancel()
            Library.refreshAll()
            return
        }

        ctx.drawShape(LayoutBounds(0f, 0f, ui.lw, ui.lh), ShapeStyle(Color(0f, 0f, 0f, 0.45f)))
        val w = 320f
        val h = 415f
        val box = LayoutBounds((ui.lw - w) / 2f, (ui.lh - h) / 2f, w, h)
        ctx.drawGlass(box, Theme.GLASS_WINDOW)

        val title = I18n.text("登录 ") + currentLogin.platformName
        ui.text(title, box.x + 20f, box.y + 20f, 17f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val closeB = LayoutBounds(box.right - 38f, box.y + 14f, 24f, 24f)
        ctx.drawShape(closeB, ShapeStyle(if (ui.hover(closeB)) Theme.CARD_HOVER else Theme.CARD, 12f))
        ui.text("x", closeB.x + 8.5f, closeB.y + 7f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        hits += closeB to { close() }

        // 平台切换 Tab
        val tabY = box.y + 48f
        val tabW = (w - 32f) / availableLogins.size
        availableLogins.forEachIndexed { i, login ->
            val tb = LayoutBounds(box.x + 16f + i * tabW, tabY, tabW, 26f)
            val selected = (login == currentLogin)
            if (selected) {
                ctx.drawShape(tb, ShapeStyle(RED, 13f))
            } else if (ui.hover(tb)) {
                ctx.drawShape(tb, ShapeStyle(Theme.CARD_HOVER, 13f))
            }
            val textCol = if (selected) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY
            val tw = ui.width(login.platformName, 11f, Theme.FONT_MEDIUM)
            ui.text(login.platformName, tb.x + (tabW - tw) / 2f, tb.y + 7f, 11f, textCol, Theme.FONT_MEDIUM)
            hits += tb to { selectLogin(login) }
        }

        val qs = 190f
        val qrBox = LayoutBounds(box.x + (w - qs) / 2f, tabY + 38f, qs, qs)
        ctx.drawShape(qrBox.expand(10f, 10f), ShapeStyle(Color.WHITE, 14f))
        val loc = qrLoc
        val showQr = currentLogin.qrImage != null && loc != null &&
            (state == AccountLogin.State.WAITING || state == AccountLogin.State.SCANNED || state == AccountLogin.State.EXPIRED)
        if (showQr) {
            ctx.pushOpacity(if (state == AccountLogin.State.EXPIRED) 0.2f else 1f)
            ctx.drawImage(qrBox, loc!!)
            ctx.popOpacity()
        } else {
            val t = if (state == AccountLogin.State.ERROR) I18n.text("获取二维码失败") else I18n.text("正在获取二维码…")
            ui.text(t, qrBox.x + (qs - ui.width(t, 13f)) / 2f, qrBox.y + qs / 2f - 6f, 13f, Color(0.33f, 0.33f, 0.33f, 1f))
        }

        val msg = when (state) {
            AccountLogin.State.WAITING -> I18n.text(currentLogin.scanPrompt)
            AccountLogin.State.SCANNED -> I18n.text("已扫码，请在手机上确认登录")
            AccountLogin.State.EXPIRED -> I18n.text("二维码已过期")
            AccountLogin.State.ERROR -> I18n.text("出错了，请重试")
            else -> I18n.text("请稍候…")
        }
        val msgColor = when (state) {
            AccountLogin.State.SCANNED -> Theme.GREEN
            AccountLogin.State.ERROR, AccountLogin.State.EXPIRED -> RED
            else -> Theme.TEXT_PRIMARY
        }
        ui.text(msg, box.x + (w - ui.width(msg, 13f)) / 2f, qrBox.bottom + 22f, 13f, msgColor)

        if (state == AccountLogin.State.EXPIRED || state == AccountLogin.State.ERROR) {
            val label = I18n.text("Refresh QR")
            val bw = ui.width(label, 12f, Theme.FONT_MEDIUM) + 28f
            val b = LayoutBounds(box.x + (w - bw) / 2f, qrBox.bottom + 46f, bw, 28f)
            ctx.drawShape(b, ShapeStyle(RED, 14f))
            ui.text(label, b.x + 14f, b.y + 9f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            hits += b to { currentLogin.start() }
        }
    }

    fun mouseClicked(mx: Int, my: Int, button: Int) {
        if (button == 0) hits.firstOrNull { it.first.contains(mx, my) }?.second?.invoke()
    }

    fun keyTyped(keyCode: Int) {
        if (keyCode == Keys.ESCAPE) close()
    }
}
