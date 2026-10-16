package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.party.Firebase
import me.sorihiko.dango.party.ListenParty
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

/** 一起听歌：开房间 / 输房间码加入 / 房间里有谁 */
internal class PartyPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    var codeInput = ""
    var codeFocused = false
    var codeRect = LayoutBounds()
        private set
    private var copiedAt = 0L

    fun draw(area: LayoutBounds) {
        val ctx = ui.ctx
        val x = area.x
        var y = area.y
        val role = ListenParty.role
        ui.text("Listen Together", x, y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        val sub = when (role) {
            ListenParty.Role.NONE -> "Play the same song in sync with friends who also have this mod"
            ListenParty.Role.HOST -> "You are the host · everyone in the room follows your music"
            ListenParty.Role.MEMBER -> "Following " + ListenParty.hostName
        }
        ui.text(sub, x, y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        y += 66f

        if (!Firebase.configured) {
            ui.text("Listen Together is not set up (missing firebase.properties)", x, y, 12f, RED, Theme.FONT_MEDIUM)
            return
        }

        if (role == ListenParty.Role.NONE) {
            ui.pill(LayoutBounds(x, y, 190f, 38f), RED, if (ListenParty.busy) "Working..." else "Create a room", "♬") { ListenParty.create() }
            y += 62f
            ui.text("OR JOIN WITH A ROOM CODE", x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 18f
            codeRect = LayoutBounds(x, y, 190f, 38f)
            ui.card(codeRect, if (codeFocused) Theme.CARD_HOVER else Theme.CARD, 12f)
            if (codeFocused) ctx.drawShape(codeRect, ShapeStyle(Color(0f, 0f, 0f, 0f), 12f, 1f, RED))
            val caret = if (codeFocused && caretVisible()) "|" else ""
            if (codeInput.isEmpty() && !codeFocused) {
                ui.text("ROOM CODE", codeRect.x + 14f, codeRect.y + 12f, 14f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            } else {
                ui.text(codeInput + caret, codeRect.x + 14f, codeRect.y + 10f, 18f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            }
            ui.hits += codeRect to {
                codeFocused = true
                screen.sidebar.searchFocused = false
                screen.tracks.filterFocused = false
            }
            ui.pill(LayoutBounds(codeRect.right + 10f, y, 90f, 38f), Color(1f, 1f, 1f, 0.12f), "Join") {
                codeFocused = false
                ListenParty.join(codeInput)
            }
            y += 56f
        } else {
            ui.text("ROOM CODE", x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 16f
            val code = ListenParty.code
            ui.text(code, x, y, 40f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            val cw = ui.width(code, 40f, Theme.FONT_DISPLAY)
            val copied = System.currentTimeMillis() - copiedAt < 1500
            ui.pill(LayoutBounds(x + cw + 18f, y + 8f, 80f, 28f), Color(1f, 1f, 1f, 0.12f), if (copied) "Copied" else "Copy") {
                DangoClient.platform.clipboard = code
                copiedAt = System.currentTimeMillis()
            }
            y += 66f

            val list = ListenParty.members
            ui.text("IN THIS ROOM · " + list.size, x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 18f
            for (m in list.take(10)) {
                val row = LayoutBounds(x, y, 320f, 34f)
                ui.card(row, Theme.CARD, 10f)
                val av = LayoutBounds(row.x + 8f, row.y + 6f, 22f, 22f)
                ctx.drawShape(av, ShapeStyle(if (m.uid == ListenParty.hostUid) RED else Theme.TRACK, 11f))
                ui.icon(m.name.take(1).uppercase(), av.x + 11f, av.y + 11f, 11f, Theme.TEXT_PRIMARY)
                val label = m.name + if (m.uid == Firebase.uid) "  (you)" else ""
                ui.text(ui.trim(label, 200f, 12f), av.right + 10f, row.y + 11f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                if (m.uid == ListenParty.hostUid) {
                    val hb = LayoutBounds(row.right - 54f, row.y + 8f, 44f, 18f)
                    ctx.drawShape(hb, ShapeStyle(RED.copy(a = 0.25f), 9f))
                    ui.text("HOST", hb.x + (hb.width - ui.width("HOST", 9f, Theme.FONT_SEMIBOLD)) / 2f, hb.y + 5f, 9f, RED, Theme.FONT_SEMIBOLD)
                }
                y += 40f
            }
            y += 10f
            ui.pill(LayoutBounds(x, y, 140f, 34f), Color(1f, 1f, 1f, 0.12f), if (role == ListenParty.Role.HOST) "Close room" else "Leave room") {
                ListenParty.leave()
            }
            if (role == ListenParty.Role.MEMBER) {
                ui.pill(LayoutBounds(x + 150f, y, 120f, 34f), Color(1f, 1f, 1f, 0.12f), "Resync", "↻") { ListenParty.resync() }
            }
            y += 50f
        }

        val st = ListenParty.status
        if (st.isNotEmpty()) ui.text(st, x, y, 12f, RED, Theme.FONT_MEDIUM)
        ui.text("Everyone streams from their own NetEase account, so VIP songs need VIP on each side.",
            x, area.bottom - 20f, 10f, Theme.TEXT_TERTIARY)
    }

    /** 房间码输入框有焦点时的按键；返回 true 表示按键被吃掉了 */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        if (!codeFocused) return false
        when {
            keyCode == Keys.ESCAPE -> codeFocused = false
            keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> {
                codeFocused = false
                ListenParty.join(codeInput)
            }
            keyCode == Keys.BACK -> if (codeInput.isNotEmpty()) codeInput = codeInput.dropLast(1)
            DangoClient.platform.ctrlDown && keyCode == Keys.V ->
                codeInput = DangoClient.platform.clipboard.filter { it.isLetterOrDigit() }.uppercase().take(8)
            typedChar.isLetterOrDigit() && typedChar.code < 128 -> codeInput = (codeInput + typedChar.uppercaseChar()).take(8)
        }
        return true
    }
}
