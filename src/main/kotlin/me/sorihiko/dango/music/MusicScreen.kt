package me.sorihiko.dango.music

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.config.ConfigManager
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.impls.music.DynamicIslandModule
import me.sorihiko.dango.module.impls.music.LyricsModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.impls.music.SongCardModule
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.party.Firebase
import me.sorihiko.dango.party.ListenParty
import me.sorihiko.dango.ui.GlassRenderContext
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.glass.GlassBackdrop
import me.sorihiko.dango.utils.Color
import me.sorihiko.dango.utils.render.GlStateSnapshot
import me.sorihiko.dango.utils.render.img.MCResImage

import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ChatAllowedCharacters
import net.minecraft.util.ResourceLocation
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 内置网易云播放器界面（液态玻璃风格，排版参考 MuoniumPlayer）：
 *   左侧玻璃侧栏：搜索框 / 主页 · 排行榜 · 正在播放 / 我的歌单 / 刷新 随机 视图 设置 / 账号
 *   右侧：主页推荐歌单墙、排行榜墙、歌单详情（列表或封面墙）、搜索结果、播放队列
 *   底部播放栏：左边当前歌曲，中间控制按钮 + 进度条，右边随机 + 音量
 *
 * 菜单字体（Inter）只有拉丁字符，所以中文（歌名、歌手、提示）和图标符号用 Minecraft 自带字体画。
 */
class MusicScreen(
    private val module: NeteaseMusicModule,
    /** 从客户端菜单打开时，关闭后回到菜单 */
    private val parent: GuiScreen? = null,
) : GuiScreen() {

    private enum class Page { HOME, CHARTS, PLAYLIST, SEARCH, QUEUE, PARTY, SETTINGS }

    companion object {
        private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "Dango-MusicUI").apply { isDaemon = true } }

        /** 网易云红（和菜单里 Music 分类同色） */
        private val RED = Color.fromHex("#FF375F")

        @Volatile private var playlists: List<NeteasePlaylist> = emptyList()
        @Volatile private var playlistsLoading = false
        @Volatile private var status = ""
        private val tracksCache = ConcurrentHashMap<Long, List<NeteaseTrack>>()
        private val tracksLoading = ConcurrentHashMap.newKeySet<Long>()

        @Volatile private var recommend: List<NeteasePlaylist>? = null
        @Volatile private var recommendLoading = false
        @Volatile private var charts: List<NeteasePlaylist>? = null
        @Volatile private var chartsLoading = false

        private var page = Page.HOME
        /** 设置页：0 = Lyrics，1 = Music & HUD；以及从哪一页点进来的 */
        private var settingsTab = 0
        private var settingsBack = Page.HOME
        private var backPage: Page? = null
        private var opened: NeteasePlaylist? = null
        /** 歌单页显示方式：false = 列表，true = 封面墙 */
        private var coverView = false
        @Volatile private var searchResults: List<NeteaseTrack>? = null
        @Volatile private var searching = false
        private var lastQuery = ""

        // 扫码登录
        private var showLogin = false
        private var qrLoc: ResourceLocation? = null
        private var qrUploaded = -1

        // 液态玻璃渲染（和菜单同一套）
        private var context: GlassRenderContext? = null
        private var backdrop: GlassBackdrop? = null

        /** 上次从网易云读歌单的时间：打开播放器时超过 [STALE_MS] 就在后台自动刷新 */
        private var lastLoadAt = 0L
        private const val STALE_MS = 5 * 60_000L

        fun reloadPlaylists() {
            if (playlistsLoading) return
            playlistsLoading = true
            lastLoadAt = System.currentTimeMillis()
            status = "正在读取歌单…"
            worker.execute {
                try {
                    NeteaseApi.ensureCookieFile()
                    val list = NeteaseApi.userPlaylists()
                    playlists = list
                    status = if (list.isEmpty()) "没有读到歌单（检查网络或登录）" else ""
                } catch (e: Exception) {
                    status = "读取歌单失败"
                } finally {
                    playlistsLoading = false
                }
            }
        }

        private fun loadRecommend() {
            if (recommend != null || recommendLoading) return
            recommendLoading = true
            worker.execute {
                recommend = try { NeteaseApi.recommendPlaylists() } catch (_: Exception) { emptyList() }
                recommendLoading = false
            }
        }

        private fun loadCharts() {
            if (charts != null || chartsLoading) return
            chartsLoading = true
            worker.execute {
                charts = try { NeteaseApi.toplists() } catch (_: Exception) { emptyList() }
                chartsLoading = false
            }
        }
    }

    // ---------------------------------------------------------------- 状态

    private var uiScale = 1f
    private var lw = 0f
    private var lh = 0f
    private var mx = 0
    private var my = 0
    private var openedAt = 0L
    private var lastNanos = 0L

    private var searchText = ""
    private var searchFocused = false

    // 歌单内筛选 + 定位到正在播放
    private var filterText = ""
    private var filterFocused = false
    private var filterRect = LayoutBounds()
    private var filterCacheSrc: List<NeteaseTrack>? = null
    private var filterCacheKey = ""
    private var filterCache: List<Pair<Int, NeteaseTrack>> = emptyList()
    private var listRows = 10

    // 歌单滚动条
    private var sbTrack = LayoutBounds()
    private var sbTotal = 0
    private var sbDragging = false
    private var sbGrab = 0f
    private var flashId = 0L
    private var flashAt = 0L
    private var leftScroll = 0
    private var rightScroll = 0
    /** 平滑滚动的当前位置（单位：行，可以是小数），向 leftScroll / rightScroll 缓动 */
    private var leftPos = 0f
    private var listPos = 0f

    /** 把 pos 缓动到 target；跳得太远（定位、拖滚动条、Home/End）直接到位，免得中间几百行封面全去下载 */
    private fun easeScroll(pos: Float, target: Int, snapRows: Int): Float {
        if (abs(pos - target) > snapRows) return target.toFloat()
        val next = pos + (target - pos) * (1f - exp(-frameDt / 70f))
        return if (abs(next - target) < 0.002f) target.toFloat() else next
    }

    // 歌单墙平滑滚动（逻辑像素）
    private var gridTarget = 0f
    private var gridPos = 0f
    private var gridMax = 0f

    // 封面墙
    private var cfTarget = 0
    private var cfPos = 0f

    private val hits = mutableListOf<Pair<LayoutBounds, () -> Unit>>()
    private val loginHits = mutableListOf<Pair<LayoutBounds, () -> Unit>>()

    /** 中英混排：英文用菜单字体，中日韩 / 符号用系统字体（玻璃层画完后统一画） */
    private val mt = MixedText()

    // 需要在点击 / 滚轮时用到的区域
    private var sideList = LayoutBounds()
    private var contentList = LayoutBounds()
    private var gridArea = LayoutBounds()
    private var progRect = LayoutBounds()
    private var volRect = LayoutBounds()
    private var searchRect = LayoutBounds()

    private val ctx: GlassRenderContext get() = context!!

    override fun initGui() {
        Keyboard.enableRepeatEvents(true)
        if (context == null) {
            context = GlassRenderContext()
            backdrop = GlassBackdrop()
            context!!.backdrop = backdrop
        }
        openedAt = System.currentTimeMillis()
        if (playlists.isEmpty()) reloadPlaylists()
        else if (System.currentTimeMillis() - lastLoadAt > STALE_MS) softRefresh()
        loadRecommend()
    }

    override fun onGuiClosed() {
        Keyboard.enableRepeatEvents(false)
    }

    override fun doesGuiPauseGame() = false

    private fun close() {
        mc.displayGuiScreen(parent)
    }

    // ---------------------------------------------------------------- 文字 / 图形工具

    /** y 是文字顶部 */
    private fun text(s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    /** 图标符号（⌂ ≡ ♫ ♥ ↻ ⇄ ▶ ◀ …），按中心点放 */
    private fun icon(s: String, cx: Float, cy: Float, size: Float, color: Color) =
        mt.icon(ctx, s, cx, cy, size, color)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): String =
        mt.trim(s, maxW, size, font)

    private fun wrap2(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): List<String> =
        mt.wrap2(s, maxW, size, font)

    private fun scissor(b: LayoutBounds) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(
            (b.x * uiScale).toInt(),
            (mc.displayHeight - b.bottom * uiScale).toInt(),
            max(0, (b.width * uiScale).toInt()),
            max(0, (b.height * uiScale).toInt()),
        )
    }

    private fun beginClip(b: LayoutBounds) {
        mt.clip = b
        scissor(b)
    }

    private fun endClip() {
        mt.clip = null
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
    }

    private fun hover(b: LayoutBounds) = b.contains(mx, my)

    private fun card(b: LayoutBounds, color: Color, radius: Float = 10f) =
        ctx.drawShape(b, ShapeStyle(color, radius))

    private fun drawCover(b: LayoutBounds, loc: ResourceLocation?, radius: Float = 8f) {
        if (loc != null) ctx.drawImage(b, MCResImage(loc)) else card(b, Theme.TRACK, radius)
    }

    private fun fmt(ms: Long): String {
        val s = max(0L, ms / 1000)
        return "${s / 60}:${"%02d".format(s % 60)}"
    }

    // ---------------------------------------------------------------- 绘制

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val snap = GlStateSnapshot()
        try {
            GL20.glUseProgram(0)
            backdrop?.capture()

            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDisable(GL11.GL_CULL_FACE)

            uiScale = min(mc.displayWidth / 1280f, mc.displayHeight / 800f).coerceAtLeast(0.5f)
            lw = mc.displayWidth / uiScale
            lh = mc.displayHeight / uiScale
            mx = (Mouse.getX() / uiScale).toInt()
            my = ((mc.displayHeight - Mouse.getY() - 1) / uiScale).toInt()
            ctx.setMousePos(mx, my)

            val now = System.nanoTime()
            val dt = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1e6f).coerceAtMost(50f)
            lastNanos = now
            frameDt = dt
            gridPos += (gridTarget - gridPos) * (1f - exp(-dt / 70f))
            cfPos += (cfTarget - cfPos) * (1f - exp(-dt / 90f))
            if (abs(cfTarget - cfPos) < 0.001f) cfPos = cfTarget.toFloat()
            setPos += (setTarget - setPos) * (1f - exp(-dt / 70f))
            // 设置页滑条：按住拖动
            if (dragValue != null) {
                if (Mouse.isButtonDown(0)) applyDrag() else dragValue = null
            }

            hits.clear()
            keyButtons.clear()
            mt.clear()
            if (!sbDragging) sbTotal = 0   // 没画歌单列表的页面上不响应滚动条
            CoverCache.upload()
            uploadQr()

            val alpha = ((System.currentTimeMillis() - openedAt) / 180f).coerceIn(0f, 1f)
            ctx.pushScale(uiScale, uiScale, 1f)
            ctx.pushOpacity(alpha)
            ctx.drawShape(LayoutBounds(0f, 0f, lw, lh), ShapeStyle(Color(0f, 0f, 0f, 0.22f)))
            drawWindow(alpha)
            ctx.popOpacity()
            ctx.popTransform()
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            mt.flush(uiScale)

            if (showLogin) {
                ctx.pushScale(uiScale, uiScale, 1f)
                drawLogin()
                ctx.popTransform()
                mt.flush(uiScale)
            }
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            snap.restore()
        }
    }

    // ---------------------------------------------------------------- 切换动画（照主菜单）
    //   侧栏选中块：上下两条边分开动，前面那条边 220ms、后面那条 380ms，像果冻一样拉伸着滑过去
    //   右侧内容：标题从右边 12px 滑入淡入（320ms），条目从下面 18px 升起淡入（380ms，每个晚 35ms）

    private var frameDt = 16f
    private var pageAt = 0L
    private var pageKey = ""

    private var selKey = ""
    private var selTarget: LayoutBounds? = null
    private var pillKey = ""
    private var pillTop = 0f
    private var pillBot = 0f
    private var pillAlpha = 0f
    private var pillFromTop = 0f
    private var pillFromBot = 0f
    private var pillAt = 0L
    private var pillDown = false

    private fun easeOut(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    private fun pageProgress(delayMs: Float, durMs: Float): Float =
        easeOut((System.currentTimeMillis() - pageAt - delayMs) / durMs)

    private inline fun shifted(dx: Float, dy: Float, alpha: Float, block: () -> Unit) {
        if (dx == 0f && dy == 0f && alpha >= 1f) {
            block()
            return
        }
        ctx.pushTranslate(dx, dy, 0f)
        ctx.pushOpacity(alpha)
        mt.shiftX += dx
        mt.shiftY += dy
        try {
            block()
        } finally {
            mt.shiftX -= dx
            mt.shiftY -= dy
            ctx.popOpacity()
            ctx.popTransform()
        }
    }

    /** 页面标题区：从右边滑入 */
    private inline fun headerAnim(block: () -> Unit) {
        val p = pageProgress(0f, 320f)
        shifted((1f - p) * 12f, 0f, p, block)
    }

    /** 第 i 个条目：从下面升起，逐个错开 */
    private inline fun itemAnim(i: Int, block: () -> Unit) {
        val p = pageProgress(min(i, 14) * 35f, 380f)
        shifted(0f, (1f - p) * 18f, p, block)
    }

    /** 侧栏选中块（用上一帧记下的选中行位置） */
    private fun drawSelectionPill(side: LayoutBounds) {
        val now = System.currentTimeMillis()
        val tgt = selTarget
        if (tgt != null) {
            if (pillAlpha < 0.02f || pillKey.isEmpty()) {
                pillTop = tgt.y
                pillBot = tgt.bottom
                pillKey = selKey
                pillAt = 0L
            } else if (selKey != pillKey) {
                pillFromTop = pillTop
                pillFromBot = pillBot
                pillDown = tgt.y > pillTop
                pillAt = now
                pillKey = selKey
            }
            if (pillAt != 0L) {
                val e = (now - pillAt).toFloat()
                val pt = e / (if (pillDown) 380f else 220f)
                val pb = e / (if (pillDown) 220f else 380f)
                pillTop = pillFromTop + (tgt.y - pillFromTop) * easeOut(pt)
                pillBot = pillFromBot + (tgt.bottom - pillFromBot) * easeOut(pb)
                if (pt >= 1f && pb >= 1f) pillAt = 0L
            } else {
                pillTop = tgt.y
                pillBot = tgt.bottom
            }
            pillAlpha = min(1f, pillAlpha + frameDt / 120f)
        } else {
            pillAlpha = max(0f, pillAlpha - frameDt / 120f)
            if (pillAlpha <= 0f) pillKey = ""
        }
        if (pillAlpha > 0.01f && pillBot - pillTop > 2f) {
            ctx.pushOpacity(pillAlpha)
            ctx.drawGlass(LayoutBounds(side.x + 6f, pillTop, side.width - 12f, pillBot - pillTop), Theme.GLASS_SELECTION)
            ctx.popOpacity()
        }
    }

    private fun drawWindow(alpha: Float) {
        // 几乎铺满屏幕，和 MuoniumPlayer 一样
        val mX = 26f
        val mY = 20f
        val win = LayoutBounds(mX, mY + (1f - alpha) * 12f, lw - mX * 2, lh - mY * 2)
        ctx.drawGlass(win, Theme.GLASS_WINDOW)

        val pad = 10f
        val sideW = 224f
        val barH = 74f
        val side = LayoutBounds(win.x + pad, win.y + pad, sideW, win.height - pad * 2)
        val mainX = side.right + pad + 8f
        val mainW = win.right - pad - 8f - mainX
        val bar = LayoutBounds(side.right + pad, win.bottom - pad - barH, win.right - pad - (side.right + pad), barH)
        val content = LayoutBounds(mainX, win.y + pad + 14f, mainW, bar.y - 10f - (win.y + pad + 14f))

        drawSidebar(side)
        drawContent(content)
        drawBar(bar)
    }

    // ---------------------------------------------------------------- 侧栏

    private fun drawSidebar(side: LayoutBounds) {
        ctx.drawGlass(side, Theme.GLASS_SIDEBAR)
        drawSelectionPill(side)
        var newKey = ""
        var newTarget: LayoutBounds? = null
        val x = side.x + 12f
        val iw = side.width - 24f
        var y = side.y + 12f

        // 搜索框：放大镜 + 输入 + 右侧"Songs"标签
        searchRect = LayoutBounds(x, y, iw, 32f)
        card(searchRect, if (searchFocused) Theme.CARD_HOVER else Theme.CARD, 10f)
        if (searchFocused) ctx.drawShape(searchRect, ShapeStyle(Color(0f, 0f, 0f, 0f), 10f, 1f, RED))
        ctx.drawShape(LayoutBounds(x + 11f, y + 10f, 9f, 9f), ShapeStyle(Color(0f, 0f, 0f, 0f), 4.5f, 1.4f, Theme.TEXT_SECONDARY))
        ctx.drawShape(LayoutBounds(x + 18.5f, y + 18f, 4.5f, 1.6f), ShapeStyle(Theme.TEXT_SECONDARY, 0.8f))
        val caret = if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "|" else ""
        val shown = if (searchText.isEmpty() && !searchFocused) "Search songs..." else searchText + caret
        val shownColor = if (searchText.isEmpty() && !searchFocused) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY
        val maxW = searchRect.right - 10f - (x + 30f)
        var visible = shown
        while (visible.length > 1 && width(visible, 12f) > maxW) visible = visible.substring(1)   // 太长时显示末尾
        text(visible, x + 30f, y + 10f, 12f, shownColor)
        hits += searchRect to { searchFocused = true }
        y += 42f

        fun section(label: String) {
            text(label, x + 2f, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 16f
        }

        fun nav(glyph: String, label: String, active: Boolean, redIcon: Boolean, action: () -> Unit) {
            val row = LayoutBounds(side.x + 6f, y, side.width - 12f, 30f)
            if (active) {
                newKey = "nav:$glyph"
                newTarget = row
            } else if (hover(row)) {
                card(row, Theme.CARD_HOVER, 9f)
            }
            icon(glyph, row.x + 18f, row.y + 15f, 12f, if (active || redIcon) RED else Theme.TEXT_SECONDARY)
            text(label, row.x + 34f, row.y + 10f, 12f, if (active) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
            hits += row to action
            y += 32f
        }

        section("DANGO MUSIC")
        nav("⌂", "Home", page == Page.HOME, true) { go(Page.HOME) }
        section("DISCOVER")
        nav("▤", "Charts", page == Page.CHARTS, false) { go(Page.CHARTS) }
        nav("♫", "Now Playing", page == Page.QUEUE, false) { go(Page.QUEUE) }
        nav("♬", if (ListenParty.role == ListenParty.Role.NONE) "Listen Together" else "Listen Together · " + ListenParty.members.size,
            page == Page.PARTY, ListenParty.role != ListenParty.Role.NONE) { go(Page.PARTY) }
        y += 4f
        section("MY PLAYLISTS")

        // 歌单列表（底部留出按钮和账号的位置）
        val rowH = 30f
        val footerH = 92f
        sideList = LayoutBounds(side.x + 6f, y, side.width - 12f, side.bottom - footerH - y)
        val rows = max(1, (sideList.height / rowH).toInt())
        val list = playlists
        leftScroll = leftScroll.coerceIn(0, max(0, list.size - rows))
        leftPos = easeScroll(leftPos, leftScroll, rows * 3)
        val firstOwned = list.indexOfFirst { it.owned }
        val leftFirst = kotlin.math.floor(leftPos).toInt()
        val leftFrac = leftPos - leftFirst
        beginClip(sideList)
        for (r in 0..rows) {
            val i = leftFirst + r
            if (i < 0) continue
            if (i >= list.size) break
            val p = list[i]
            val row = LayoutBounds(sideList.x, sideList.y + (r - leftFrac) * rowH, sideList.width, rowH - 2f)
            val active = page == Page.PLAYLIST && opened?.id == p.id
            if (active) {
                newKey = "pl:${p.id}"
                newTarget = row
            } else if (hover(row)) {
                card(row, Theme.CARD_HOVER, 9f)
            }
            val liked = i == firstOwned   // 第一个自己的歌单就是"我喜欢的音乐"
            icon(if (liked) "♥" else "≡", row.x + 18f, row.y + 14f, 12f, if (liked || active) RED else Theme.TEXT_SECONDARY)
            val c = if (active) Theme.TEXT_PRIMARY else if (p.owned) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY
            text(trim(p.name, row.right - 8f - (row.x + 34f), 12f), row.x + 34f, row.y + 9f, 12f, c)
            hits += row.intersect(sideList) to { openPlaylist(p, null) }
        }
        endClip()
        if (list.isEmpty()) {
            val msg = if (playlistsLoading) "Loading..." else if (NeteaseApi.loggedIn) "No playlists" else "Log in to see your playlists"
            text(msg, x + 2f, sideList.y + 6f, 11f, Theme.TEXT_TERTIARY)
        }

        // 设置按钮（歌单会在打开播放器时自动刷新，不需要刷新按钮）
        val by = side.bottom - footerH + 8f
        val sb = LayoutBounds(x, by, iw, 32f)
        val on = page == Page.SETTINGS
        card(sb, if (on) RED.copy(a = 0.22f) else if (hover(sb)) Theme.CARD_HOVER else Theme.CARD, 10f)
        if (on) ctx.drawShape(sb, ShapeStyle(Color(0f, 0f, 0f, 0f), 10f, 1f, RED.copy(a = 0.6f)))
        val sCol = if (on) RED else Theme.TEXT_PRIMARY
        val sLabel = "Settings"
        val sw = 14f + 6f + width(sLabel, 12f, Theme.FONT_SEMIBOLD)
        val sx = sb.x + (sb.width - sw) / 2f
        icon("⚙", sx + 7f, sb.y + 16f, 14f, sCol)
        text(sLabel, sx + 20f, sb.y + 16f - 12f * 0.39f - 0.5f, 12f, sCol, Theme.FONT_SEMIBOLD)
        hits += sb to { toggleSettings() }

        // 账号
        val ub = LayoutBounds(side.x + 6f, side.bottom - 46f, side.width - 12f, 40f)
        val av = LayoutBounds(ub.x + 6f, ub.y + 6f, 28f, 28f)
        if (NeteaseApi.loggedIn) {
            val aurl = NeteaseApi.avatarUrl
            drawCover(av, if (aurl.isNotEmpty()) CoverCache.get("u:${NeteaseApi.userId}", aurl) else null, 14f)
            val who = NeteaseApi.nickname.ifEmpty { "Logged in" }
            val outW = width("Log out", 10f, Theme.FONT_MEDIUM)
            text(trim(who, ub.right - outW - 18f - (av.right + 10f), 12f), av.right + 10f, ub.y + 14f, 12f, Theme.TEXT_PRIMARY)
            val outB = LayoutBounds(ub.right - outW - 14f, ub.y + 10f, outW + 10f, 20f)
            if (hover(outB)) card(outB, Theme.CARD_HOVER, 6f)
            text("Log out", outB.x + 5f, outB.y + 6f, 10f, Theme.TEXT_TERTIARY, Theme.FONT_MEDIUM)
            hits += outB to {
                NeteaseApi.logout()
                refreshAll()
            }
        } else {
            if (hover(ub)) card(ub, Theme.CARD_HOVER, 10f)
            ctx.drawShape(av, ShapeStyle(Theme.TRACK, 14f))
            icon("?", av.x + 14f, av.y + 14f, 12f, Theme.TEXT_SECONDARY)
            text("Log in to NetEase", av.right + 10f, ub.y + 14f, 12f, RED, Theme.FONT_SEMIBOLD)
            hits += ub to { openLogin() }
        }
        // 下一帧选中块往这里动
        selKey = newKey
        selTarget = newTarget
    }

    private fun go(p: Page) {
        clearFilter()
        page = p
        backPage = null
        rightScroll = 0
        gridTarget = 0f
        gridPos = 0f
        when (p) {
            Page.HOME -> loadRecommend()
            Page.CHARTS -> loadCharts()
            Page.QUEUE -> if (coverView) focusCurrentInCoverflow()
            else -> {}
        }
    }

    private fun goBack() {
        if (page == Page.SETTINGS) {
            page = settingsBack
            return
        }
        val b = backPage ?: return
        page = b
        backPage = null
    }

    private fun openPlaylist(p: NeteasePlaylist, from: Page?) {
        if (opened?.id != p.id) clearFilter()
        opened = p
        page = Page.PLAYLIST
        backPage = from
        rightScroll = 0
        cfTarget = 0
        cfPos = 0f
        if (tracksCache.containsKey(p.id) || !tracksLoading.add(p.id)) return
        worker.execute {
            try {
                tracksCache[p.id] = NeteaseApi.playlistTracks(p.id)
            } catch (_: Exception) {
                tracksCache[p.id] = emptyList()
            } finally {
                tracksLoading.remove(p.id)
            }
        }
    }

    private fun doSearch() {
        val q = searchText.trim()
        if (q.isEmpty()) return
        searchFocused = false
        clearFilter()
        if (page != Page.SEARCH) backPage = page
        page = Page.SEARCH
        rightScroll = 0
        cfTarget = 0
        cfPos = 0f
        lastQuery = q
        searching = true
        searchResults = null
        worker.execute {
            try {
                searchResults = NeteaseApi.search(q)
            } catch (_: Exception) {
                searchResults = emptyList()
            } finally {
                searching = false
            }
        }
    }

    /**
     * 悄悄刷新：后台重新读歌单列表和已经打开过的歌单里的歌，读到了再替换，
     * 界面上不会出现"加载中"（游戏开着时在手机上新建歌单 / 加歌，下次打开播放器就能看到）
     */
    private fun softRefresh() {
        lastLoadAt = System.currentTimeMillis()
        worker.execute {
            try {
                val list = NeteaseApi.userPlaylists()
                if (list.isNotEmpty()) playlists = list
            } catch (_: Exception) {
            }
            for (id in tracksCache.keys.toList()) {
                try {
                    val tracks = NeteaseApi.playlistTracks(id)
                    if (tracks.isNotEmpty()) tracksCache[id] = tracks
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun refreshAll() {
        playlists = emptyList()
        tracksCache.clear()
        recommend = null
        charts = null
        reloadPlaylists()
        loadRecommend()
        if (page == Page.CHARTS) loadCharts()
    }

    private fun toggleCoverView() {
        coverView = !coverView
        if (coverView) focusCurrentInCoverflow()
    }

    // ---------------------------------------------------------------- 右侧内容

    private fun drawContent(area: LayoutBounds) {
        // 底部小字只在有提示（读取失败等）时显示
        val foot = status
        if (foot.isNotEmpty()) text(foot, area.x, area.bottom - 11f, 10f, RED, Theme.FONT_SEMIBOLD)
        val body = LayoutBounds(area.x, area.y, area.width, area.height - (if (foot.isNotEmpty()) 18f else 4f))
        setArea = LayoutBounds()
        // 换页（含换歌单 / 设置换标签 / 新搜索 / 列表↔封面墙）就重新播入场动画
        val key = "$page|${opened?.id}|$settingsTab|$coverView|$lastQuery"
        if (key != pageKey) {
            pageKey = key
            pageAt = System.currentTimeMillis()
            listPos = rightScroll.toFloat()
        }

        when (page) {
            Page.HOME -> {
                val rec = recommend
                val items = if (rec.isNullOrEmpty()) playlists else rec
                val label = if (rec.isNullOrEmpty()) "Your playlists" else "Recommended playlists"
                drawGridPage(body, "Welcome to Dango Music!", "$label · ${items.size}", items, recommendLoading && items.isEmpty())
            }
            Page.CHARTS -> {
                val c = charts ?: emptyList()
                drawGridPage(body, "Charts", "${c.size} charts", c, chartsLoading)
            }
            Page.PARTY -> itemAnim(0) { drawPartyPage(body) }
            Page.SETTINGS -> drawSettingsPage(body)
            else -> drawTracksPage(body)
        }
    }

    // ---------------------------------------------------------------- 设置（侧栏齿轮）

    private enum class Ctl { TOGGLE, SLIDER, SWATCH, KEY }

    /** scale：显示值 = 实际值 × scale（百分比的选项是 100），手动输入时按显示值换算回去 */
    private class SRow(
        val label: String, val desc: String, val value: Any, val ctl: Ctl,
        val dep: BooleanValue?, val fmt: (Any) -> String, val scale: Float = 1f,
    )

    /** 显示成百分比的选项 */
    private val percentKeys = setOf("volume", "glowRadius", "posX", "posY")

    // 点数值手动输入
    private var editRow: SRow? = null
    private var editText = ""
    private var editRect = LayoutBounds()

    private fun numberText(r: SRow): String = when (val v = r.value) {
        is IntValue -> (v.value * r.scale).roundToInt().toString()
        is FloatValue -> {
            val x = v.value * r.scale
            if (r.scale != 1f || v.decimals == 0) x.roundToInt().toString() else "%.${v.decimals}f".format(x)
        }
        else -> ""
    }

    private fun startEdit(r: SRow) {
        if (editRow === r) return
        commitEdit()
        editRow = r
        editText = numberText(r)
        searchFocused = false
        filterFocused = false
        codeFocused = false
        bindingKey = null
    }

    /** 输入的数字写回去（超出范围的自动夹到范围内，乱输的忽略） */
    private fun commitEdit() {
        val r = editRow ?: return
        editRow = null
        val n = editText.trim().replace(',', '.').toFloatOrNull() ?: return
        when (val v = r.value) {
            is IntValue -> v.setValue((n / r.scale).roundToInt())
            is FloatValue -> v.setValue(n / r.scale)
        }
    }

    /** owner：所属模块（模块关掉时整组变暗）；toggle：组标题右边显示模块总开关 */
    private class SGroup(val title: String, val sub: String, val owner: Module, val toggle: Boolean, val rows: List<SRow>)

    private var setTarget = 0f
    private var setPos = 0f
    private var setMax = 0f
    private var setArea = LayoutBounds()
    private var dragValue: Any? = null
    private var dragTrack = LayoutBounds()
    private var bindingKey: IntValue? = null
    private val keyButtons = mutableListOf<LayoutBounds>()

    /** 直接读各模块的 @ModuleValue，改了立刻生效，ConfigManager 会自动存盘 */
    private val settingTabs: List<List<SGroup>> by lazy { buildSettings() }

    private fun toggleSettings() {
        commitEdit()
        searchFocused = false
        filterFocused = false
        codeFocused = false
        bindingKey = null
        if (page == Page.SETTINGS) {
            page = settingsBack
        } else {
            settingsBack = page
            page = Page.SETTINGS
            setTarget = 0f
            setPos = 0f
        }
    }

    private fun fieldsOf(m: Module): LinkedHashMap<String, Pair<ModuleValue, Any>> {
        val out = LinkedHashMap<String, Pair<ModuleValue, Any>>()
        for (f in m.javaClass.declaredFields) {
            val ann = f.getAnnotation(ModuleValue::class.java) ?: continue
            f.isAccessible = true
            val v = f.get(m) ?: continue
            out[f.name] = ann to v
        }
        return out
    }

    private fun fmtValue(v: Any): String = when (v) {
        is IntValue -> v.value.toString()
        is FloatValue -> "%.${v.decimals}f".format(v.value)
        else -> ""
    }

    private fun percent(v: Any): String = "${((v as FloatValue).value * 100f).roundToInt()}%"

    private fun buildSettings(): List<List<SGroup>> {
        val mm = DangoMusic.instance.moduleManager

        /** 一个模块的设置；take(...) 按名字取出若干行，剩下的最后放进 "Other" */
        class Src(val m: Module, val deps: Map<String, String> = emptyMap(), val fmts: Map<String, (Any) -> String> = emptyMap()) {
            val all = fieldsOf(m)
            val left = LinkedHashMap(all)
            fun take(names: List<String>? = null): List<SRow> = (names ?: left.keys.toList()).mapNotNull { k ->
                val (ann, v) = left.remove(k) ?: return@mapNotNull null
                val ctl = when {
                    v is BooleanValue -> Ctl.TOGGLE
                    k == "glowColor" -> Ctl.SWATCH
                    k == "openKey" || (k.startsWith("key") && v is IntValue) -> Ctl.KEY
                    v is IntValue || v is FloatValue -> Ctl.SLIDER
                    else -> return@mapNotNull null
                }
                val dep = deps[k]?.let { all[it]?.second as? BooleanValue }
                SRow(ann.name.ifEmpty { k }, ann.desc, v, ctl, dep, fmts[k] ?: ::fmtValue, if (k in percentKeys) 100f else 1f)
            }
        }

        val ly = Src(
            mm.get(LyricsModule::class.java),
            deps = mapOf(
                "distance" to "worldMode", "facePlayer" to "worldMode", "keepAway" to "worldMode", "spread" to "worldMode",
                "glowColor" to "glow", "glowStrength" to "glow", "glowRadius" to "glow",
                "wiggleFps" to "wiggle", "wiggleAmount" to "wiggle",
                "flashSize" to "textFlash",
            ),
            fmts = mapOf(
                "spread" to { v: Any -> "${(v as FloatValue).value.roundToInt()}°" },
                "glowRadius" to ::percent,
            ),
        )
        val lyrics = mutableListOf(
            SGroup("Placement", "In the world", ly.m, false, ly.take(listOf("worldMode", "distance", "facePlayer", "keepAway", "spread", "scale", "smoothFont", "splitLines"))),
            SGroup("Glow", "Deep Glow", ly.m, false, ly.take(listOf("glow", "glowColor", "glowStrength", "glowRadius"))),
            SGroup("Motion", "Animation", ly.m, false, ly.take(listOf("wiggle", "wiggleFps", "wiggleAmount", "textFlash", "wordPop", "flashSize"))),
        )
        // 左上角的原版风格"正在播放"和 Song card 是同一类东西，放到 Music & HUD 里
        val nowPlaying = SGroup("Now playing", "Classic overlay", ly.m, false, ly.take(listOf("nowPlaying", "showCover")))
        ly.take().let { if (it.isNotEmpty()) lyrics += SGroup("Other", "More", ly.m, false, it) }

        val player = Src(module, fmts = mapOf("volume" to ::percent))
        player.left.remove("shuffle")   // 随机播放用播放栏上的按钮就够了，设置页不再显示（状态照样保存）
        val card = Src(mm.get(SongCardModule::class.java), fmts = mapOf("posX" to ::percent, "posY" to ::percent))
        val island = Src(mm.get(DynamicIslandModule::class.java), fmts = mapOf("notifyTime" to { v: Any -> "%.1f s".format((v as FloatValue).value) }))
        val music = listOf(
            SGroup("Player", "Playback", player.m, false, player.take(listOf("volume"))),
            SGroup("Hotkeys", "Click, then press a key · Backspace clears", player.m, false, player.take()),
            SGroup("Song card", "Overlay", card.m, true, card.take()),
            nowPlaying,
            SGroup("Dynamic Island", "Overlay", island.m, true, island.take()),
        )
        return listOf(lyrics, music)
    }

    private fun drawSettingsPage(area: LayoutBounds) {
        gridArea = LayoutBounds()
        contentList = LayoutBounds()
        headerAnim {
        text("Settings", area.x, area.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        text("Changes apply instantly and are saved automatically", area.x, area.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)

        // 标签：Lyrics / Music & HUD
        val names = listOf("Lyrics", "Music & HUD")
        val tabW = 104f
        val tabs = LayoutBounds(area.right - tabW * names.size - 8f, area.y + 2f, tabW * names.size + 8f, 32f)
        card(tabs, Theme.CARD, 11f)
        names.forEachIndexed { i, n ->
            val b = LayoutBounds(tabs.x + 4f + i * tabW, tabs.y + 4f, tabW, 24f)
            val on = i == settingsTab
            if (on) ctx.drawShape(b, ShapeStyle(RED, 8f)) else if (hover(b)) card(b, Theme.CARD_HOVER, 8f)
            val w = width(n, 12f, Theme.FONT_SEMIBOLD)
            text(n, b.x + (b.width - w) / 2f, b.y + 12f - 12f * 0.39f - 0.5f, 12f,
                if (on) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            hits += b to {
                if (settingsTab != i) {
                    settingsTab = i
                    setTarget = 0f
                    setPos = 0f
                    bindingKey = null
                }
            }
        }
        }

        val view = LayoutBounds(area.x, area.y + 54f, area.width, area.bottom - (area.y + 54f))
        setArea = view
        val groups = settingTabs[settingsTab.coerceIn(0, settingTabs.size - 1)]

        // 两列瀑布流：每组放进当前较矮的一列
        val gap = 14f
        val rowH = 40f
        val headH = 36f
        val cols = if (view.width >= 560f) 2 else 1
        val colW = (view.width - 12f - gap * (cols - 1)) / cols
        val colY = FloatArray(cols)
        val placed = groups.map { g ->
            val h = headH + g.rows.size * rowH + 6f
            var c = 0
            for (i in 1 until cols) if (colY[i] < colY[c] - 0.5f) c = i
            val pos = Triple(c, colY[c], h)
            colY[c] += h + gap
            pos
        }
        val contentH = max(0f, (colY.maxOrNull() ?: 0f) - gap)
        setMax = max(0f, contentH - view.height)
        setTarget = setTarget.coerceIn(0f, setMax)
        setPos = setPos.coerceIn(0f, setMax)

        beginClip(view)
        groups.forEachIndexed { gi, g ->
            val (c, gy, gh) = placed[gi]
            val b = LayoutBounds(view.x + c * (colW + gap), view.y + gy - setPos, colW, gh)
            if (b.bottom < view.y || b.y > view.bottom) return@forEachIndexed
            itemAnim(gi) { drawSettingGroup(b, g, view, rowH, headH) }
        }
        endClip()

        // 滚动条（只显示位置）
        if (setMax > 0f && contentH > 0f) {
            val th = max(30f, view.height * view.height / contentH)
            val ty = view.y + (view.height - th) * (setPos / setMax)
            ctx.drawShape(LayoutBounds(view.right - 4f, ty, 4f, th), ShapeStyle(Color(1f, 1f, 1f, 0.30f), 2f))
        }
    }

    private fun drawSettingGroup(b: LayoutBounds, g: SGroup, clip: LayoutBounds, rowH: Float, headH: Float) {
        ctx.drawShape(b, ShapeStyle(Color(1f, 1f, 1f, 0.05f), 14f))
        val x = b.x + 14f
        val iw = b.width - 28f
        text(g.title, x, b.y + 13f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text(g.sub, x + width(g.title, 13f, Theme.FONT_SEMIBOLD) + 8f, b.y + 14.5f, 10f, Theme.TEXT_TERTIARY)
        if (g.toggle) {
            val m = g.owner
            toggleSwitch(LayoutBounds(b.right - 14f - 34f, b.y + 9f, 34f, 20f), m.enabled, clip) { m.enabled = !m.enabled }
        }
        var y = b.y + headH
        val moduleOff = !g.owner.enabled
        for (r in g.rows) {
            ctx.drawShape(LayoutBounds(x, y, iw, 1f), ShapeStyle(Color(1f, 1f, 1f, 0.06f)))
            val dim = moduleOff || (r.dep != null && !r.dep.get())
            if (dim) ctx.pushOpacity(0.4f)
            drawSettingRow(LayoutBounds(x, y, iw, rowH), r, clip)
            if (dim) ctx.popOpacity()
            y += rowH
        }
    }

    private fun toggleSwitch(b: LayoutBounds, on: Boolean, clip: LayoutBounds, action: () -> Unit) {
        ctx.drawShape(b, ShapeStyle(if (on) RED else Color(1f, 1f, 1f, if (hover(b)) 0.24f else 0.16f), b.height / 2f))
        val k = b.height - 4f
        ctx.drawShape(LayoutBounds(if (on) b.right - 2f - k else b.x + 2f, b.y + 2f, k, k), ShapeStyle(Color(1f, 1f, 1f, 1f), k / 2f))
        hits += b.expand(4f, 4f).intersect(clip) to action
    }

    private val swatchColors = listOf(
        Color(1f, 1f, 1f, 1f),            // 跟随字色
        Color(1f, 0.38f, 0.62f, 1f),      // 粉
        Color(0.32f, 0.66f, 1f, 1f),      // 蓝
        Color(1f, 0.74f, 0.30f, 1f),      // 金
    )

    private fun drawSettingRow(b: LayoutBounds, r: SRow, clip: LayoutBounds) {
        val ctlW = when (r.ctl) {
            Ctl.TOGGLE -> 34f
            Ctl.SLIDER -> 166f
            Ctl.SWATCH -> 16f * 4 + 8f * 3
            Ctl.KEY -> 72f
        }
        val textW = b.width - ctlW - 14f
        text(trim(r.label, textW, 12f, Theme.FONT_MEDIUM), b.x, b.y + 8f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        if (r.desc.isNotEmpty()) text(trim(r.desc, textW, 9.5f), b.x, b.y + 24f, 9.5f, Theme.TEXT_TERTIARY)
        val cy = b.y + b.height / 2f
        val cx = b.right - ctlW
        when (r.ctl) {
            Ctl.TOGGLE -> {
                val v = r.value as BooleanValue
                toggleSwitch(LayoutBounds(cx, cy - 10f, 34f, 20f), v.get(), clip) { v.toggle() }
            }
            Ctl.SLIDER -> {
                val track = LayoutBounds(cx + 2f, cy - 2f, 108f, 4f)
                val p = when (val v = r.value) {
                    is IntValue -> v.normalize()
                    is FloatValue -> v.normalize()
                    else -> 0f
                }.coerceIn(0f, 1f)
                val active = dragValue === r.value
                if (active) dragTrack = track
                ctx.drawShape(track, ShapeStyle(Theme.TRACK, 2f))
                ctx.drawShape(LayoutBounds(track.x, track.y, track.width * p, track.height), ShapeStyle(RED, 2f))
                val k = if (active || hover(track.expand(6f, 10f))) 14f else 11f
                ctx.drawShape(LayoutBounds(track.x + track.width * p - k / 2f, cy - k / 2f, k, k), ShapeStyle(Color(1f, 1f, 1f, 1f), k / 2f))
                // 数值：点一下可以直接输入
                val vb = LayoutBounds(b.right - 46f, cy - 10f, 46f, 20f)
                if (editRow === r) {
                    editRect = vb
                    card(vb, Theme.CARD_HOVER, 6f)
                    ctx.drawShape(vb, ShapeStyle(Color(0f, 0f, 0f, 0f), 6f, 1f, RED))
                    val caret = if ((System.currentTimeMillis() / 500) % 2 == 0L) "|" else ""
                    val et = editText + caret
                    text(et, vb.right - 6f - width(et, 11f, Theme.FONT_SEMIBOLD), cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                } else {
                    val s = r.fmt(r.value)
                    val sw = width(s, 11f, Theme.FONT_SEMIBOLD)
                    val hot = hover(vb)
                    if (hot) card(LayoutBounds(b.right - sw - 6f, cy - 10f, sw + 6f, 20f), Theme.CARD_HOVER, 6f)
                    text(s, b.right - sw - (if (hot) 3f else 0f), cy - 11f * 0.39f - 0.5f, 11f,
                        if (active) RED else if (hot) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
                }
                hits += vb.intersect(clip) to { startEdit(r) }
                hits += track.expand(8f, 12f).intersect(clip) to {
                    dragValue = r.value
                    dragTrack = track
                    applyDrag()
                }
            }
            Ctl.SWATCH -> {
                val v = r.value as IntValue
                swatchColors.forEachIndexed { i, col ->
                    if (i > v.maxRange) return@forEachIndexed
                    val sb = LayoutBounds(cx + i * 24f, cy - 8f, 16f, 16f)
                    if (v.value == i) ctx.drawShape(sb.expand(3f, 3f), ShapeStyle(Color(0f, 0f, 0f, 0f), 11f, 1.5f, Theme.TEXT_PRIMARY))
                    ctx.drawShape(sb, ShapeStyle(col, 8f))
                    hits += sb.expand(4f, 4f).intersect(clip) to { v.setValue(i) }
                }
            }
            Ctl.KEY -> {
                val v = r.value as IntValue
                val kb = LayoutBounds(cx, cy - 12f, 72f, 24f)
                val binding = bindingKey === v
                card(kb, if (binding) RED.copy(a = 0.22f) else if (hover(kb)) Theme.CARD_HOVER else Theme.CARD, 7f)
                if (binding) ctx.drawShape(kb, ShapeStyle(Color(0f, 0f, 0f, 0f), 7f, 1f, RED))
                val name = when {
                    binding -> "Press a key"
                    v.value == 0 -> "None"
                    else -> Keyboard.getKeyName(v.value) ?: "#${v.value}"
                }
                val s = trim(name, kb.width - 10f, 11f, Theme.FONT_SEMIBOLD)
                text(s, kb.x + (kb.width - width(s, 11f, Theme.FONT_SEMIBOLD)) / 2f, cy - 11f * 0.39f - 0.5f, 11f,
                    if (binding) RED else if (v.value == 0) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
                val hb = kb.intersect(clip)
                keyButtons += hb
                hits += hb to { bindingKey = if (bindingKey === v) null else v }
            }
        }
    }

    private fun applyDrag() {
        val v = dragValue ?: return
        if (dragTrack.width <= 0f) return
        val p = ((mx - dragTrack.x) / dragTrack.width).coerceIn(0f, 1f)
        when (v) {
            is IntValue -> v.setValue((v.minRange + p * (v.maxRange - v.minRange)).roundToInt())
            is FloatValue -> v.setValue(v.minRange + p * (v.maxRange - v.minRange))
        }
    }

    // ---------------------------------------------------------------- 一起听

    private var codeInput = ""
    private var codeFocused = false
    private var codeRect = LayoutBounds()
    private var copiedAt = 0L

    private fun pill(b: LayoutBounds, fill: Color, label: String, glyph: String? = null, action: () -> Unit) {
        ctx.drawShape(b, ShapeStyle(if (hover(b)) fill.copy(a = fill.a * 0.85f) else fill, b.height / 2f))
        val tw = width(label, 12f, Theme.FONT_SEMIBOLD) + (if (glyph != null) 18f else 0f)
        var tx = b.x + (b.width - tw) / 2f
        if (glyph != null) {
            icon(glyph, tx + 6f, b.y + b.height / 2f, 12f, Theme.TEXT_PRIMARY)
            tx += 18f
        }
        text(label, tx, b.y + b.height / 2f - 12f * 0.39f - 0.5f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        hits += b to action
    }

    private fun drawPartyPage(area: LayoutBounds) {
        gridArea = LayoutBounds()
        contentList = LayoutBounds()
        val x = area.x
        var y = area.y
        val role = ListenParty.role
        text("Listen Together", x, y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        val sub = when (role) {
            ListenParty.Role.NONE -> "Play the same song in sync with friends who also have this mod"
            ListenParty.Role.HOST -> "You are the host · everyone in the room follows your music"
            ListenParty.Role.MEMBER -> "Following " + ListenParty.hostName
        }
        text(sub, x, y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        y += 66f

        if (!Firebase.configured) {
            text("Listen Together is not set up (missing firebase.properties)", x, y, 12f, RED, Theme.FONT_MEDIUM)
            return
        }

        if (role == ListenParty.Role.NONE) {
            pill(LayoutBounds(x, y, 190f, 38f), RED, if (ListenParty.busy) "Working..." else "Create a room", "♬") { ListenParty.create() }
            y += 62f
            text("OR JOIN WITH A ROOM CODE", x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 18f
            codeRect = LayoutBounds(x, y, 190f, 38f)
            card(codeRect, if (codeFocused) Theme.CARD_HOVER else Theme.CARD, 12f)
            if (codeFocused) ctx.drawShape(codeRect, ShapeStyle(Color(0f, 0f, 0f, 0f), 12f, 1f, RED))
            val caret = if (codeFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "|" else ""
            if (codeInput.isEmpty() && !codeFocused) {
                text("ROOM CODE", codeRect.x + 14f, codeRect.y + 12f, 14f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            } else {
                text(codeInput + caret, codeRect.x + 14f, codeRect.y + 10f, 18f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            }
            hits += codeRect to { codeFocused = true; searchFocused = false; filterFocused = false }
            pill(LayoutBounds(codeRect.right + 10f, y, 90f, 38f), Color(1f, 1f, 1f, 0.12f), "Join") {
                codeFocused = false
                ListenParty.join(codeInput)
            }
            y += 56f
        } else {
            text("ROOM CODE", x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 16f
            val code = ListenParty.code
            text(code, x, y, 40f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            val cw = width(code, 40f, Theme.FONT_DISPLAY)
            val copied = System.currentTimeMillis() - copiedAt < 1500
            pill(LayoutBounds(x + cw + 18f, y + 8f, 80f, 28f), Color(1f, 1f, 1f, 0.12f), if (copied) "Copied" else "Copy") {
                GuiScreen.setClipboardString(code)
                copiedAt = System.currentTimeMillis()
            }
            y += 66f

            val list = ListenParty.members
            text("IN THIS ROOM · " + list.size, x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 18f
            for (m in list.take(10)) {
                val row = LayoutBounds(x, y, 320f, 34f)
                card(row, Theme.CARD, 10f)
                val av = LayoutBounds(row.x + 8f, row.y + 6f, 22f, 22f)
                ctx.drawShape(av, ShapeStyle(if (m.uid == ListenParty.hostUid) RED else Theme.TRACK, 11f))
                icon(m.name.take(1).uppercase(), av.x + 11f, av.y + 11f, 11f, Theme.TEXT_PRIMARY)
                val label = m.name + if (m.uid == Firebase.uid) "  (you)" else ""
                text(trim(label, 200f, 12f), av.right + 10f, row.y + 11f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                if (m.uid == ListenParty.hostUid) {
                    val hb = LayoutBounds(row.right - 54f, row.y + 8f, 44f, 18f)
                    ctx.drawShape(hb, ShapeStyle(RED.copy(a = 0.25f), 9f))
                    text("HOST", hb.x + (hb.width - width("HOST", 9f, Theme.FONT_SEMIBOLD)) / 2f, hb.y + 5f, 9f, RED, Theme.FONT_SEMIBOLD)
                }
                y += 40f
            }
            y += 10f
            pill(LayoutBounds(x, y, 140f, 34f), Color(1f, 1f, 1f, 0.12f), if (role == ListenParty.Role.HOST) "Close room" else "Leave room") {
                ListenParty.leave()
            }
            if (role == ListenParty.Role.MEMBER) {
                pill(LayoutBounds(x + 150f, y, 120f, 34f), Color(1f, 1f, 1f, 0.12f), "Resync", "↻") { ListenParty.resync() }
            }
            y += 50f
        }

        val st = ListenParty.status
        if (st.isNotEmpty()) text(st, x, y, 12f, RED, Theme.FONT_MEDIUM)
        text("Everyone streams from their own NetEase account, so VIP songs need VIP on each side.",
            x, area.bottom - 20f, 10f, Theme.TEXT_TERTIARY)
    }

    private fun drawGridPage(area: LayoutBounds, title: String, sub: String, items: List<NeteasePlaylist>, loading: Boolean) {
        headerAnim {
            text(title, area.x, area.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            text(if (loading) "Loading..." else sub, area.x, area.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        }
        val grid = LayoutBounds(area.x, area.y + 54f, area.width, area.bottom - (area.y + 54f))
        gridArea = grid
        contentList = LayoutBounds()
        drawGrid(grid, items)
    }

    /** 歌单墙：正方形封面 + 下面两行名字，平滑滚动 */
    private fun drawGrid(area: LayoutBounds, items: List<NeteasePlaylist>) {
        val gap = 22f
        val cols = max(2, ((area.width + gap) / (160f + gap)).toInt())
        val cw = (area.width - gap * (cols - 1)) / cols
        val rowH = cw + 46f
        val totalRows = (items.size + cols - 1) / cols
        gridMax = max(0f, totalRows * rowH - area.height)
        gridTarget = gridTarget.coerceIn(0f, gridMax)
        gridPos = gridPos.coerceIn(0f, gridMax)

        // 上下多留一点空间给悬停时放大的边框
        val clip = LayoutBounds(area.x - 6f, area.y - 6f, area.width + 12f, area.height + 6f)
        beginClip(clip)
        val firstRow = max(0, (gridPos / rowH).toInt())
        val lastRow = min(totalRows - 1, ((gridPos + area.height) / rowH).toInt())
        for (r in firstRow..lastRow) {
            for (c in 0 until cols) {
                val i = r * cols + c
                if (i >= items.size) break
                val p = items[i]
                val x = area.x + c * (cw + gap)
                val y = area.y + r * rowH - gridPos
                itemAnim((r - firstRow) * cols + c) {
                val cover = LayoutBounds(x, y, cw, cw)
                val cell = LayoutBounds(x, y, cw, rowH - 6f)
                val hov = hover(cell) && clip.contains(mx, my)
                if (hov) ctx.drawShape(LayoutBounds(x - 5f, y - 5f, cw + 10f, rowH + 2f), ShapeStyle(Theme.CARD_HOVER, 14f))
                drawCover(cover, CoverCache.playlist(p), 12f)
                if (hov) {
                    // 悬停：右下角一个播放按钮
                    val pb = LayoutBounds(cover.right - 40f, cover.bottom - 40f, 30f, 30f)
                    ctx.drawShape(pb, ShapeStyle(RED, 15f))
                    icon("▶", pb.x + 16f, pb.y + 15f, 12f, Theme.TEXT_PRIMARY)
                }
                val lines = wrap2(p.name, cw - 4f, 12f, Theme.FONT_MEDIUM)
                lines.forEachIndexed { li, line ->
                    text(line, x + 2f, cover.bottom + 8f + li * 15f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                }
                val from = page
                hits += cell.intersect(area) to { openPlaylist(p, from) }
                }
            }
        }
        endClip()
    }

    private fun drawTracksPage(area: LayoutBounds) {
        gridArea = LayoutBounds()
        val (title, tracks) = currentTracks()
        var x = area.x
        val y = area.y

        var headerH = 62f
        headerAnim {
        // 返回
        if (backPage != null) {
            val bb = LayoutBounds(x, y + 2f, 28f, 28f)
            ctx.drawShape(bb, ShapeStyle(if (hover(bb)) Theme.CARD_HOVER else Theme.CARD, 14f))
            icon("◀", bb.x + 13.5f, bb.y + 14f, 10f, Theme.TEXT_PRIMARY)
            hits += bb to { goBack() }
            x += 40f
        }

        // 歌单封面
        val p = opened
        if (page == Page.PLAYLIST && p != null) {
            val cv = LayoutBounds(x, y, 96f, 96f)
            drawCover(cv, CoverCache.playlist(p), 12f)
            x = cv.right + 18f
            headerH = 112f
        }

        val header = when (page) {
            Page.SEARCH -> "Search  \"$lastQuery\""
            Page.QUEUE -> "Now Playing"
            else -> title
        }
        val segW = 132f
        text(trim(header, area.right - segW - 24f - x, 22f, Theme.FONT_DISPLAY), x, y + 4f, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        val sub = when {
            page == Page.SEARCH && searching -> "Searching..."
            page == Page.SEARCH && tracks.isNullOrEmpty() ->
                if (NeteaseApi.lastSearchError.isNotEmpty()) "Search failed: " + NeteaseApi.lastSearchError else "No results"
            page == Page.QUEUE && tracks.isNullOrEmpty() -> "Nothing in the queue yet"
            tracks == null -> "Loading..."
            filterText.isNotEmpty() -> "${shown(tracks).size} of ${tracks.size} songs"
            else -> "${tracks.size} songs"
        }
        text(sub, x, y + 34f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)

        // 播放全部
        if (!tracks.isNullOrEmpty() && page != Page.QUEUE) {
            val label = "Play all"
            val pb = LayoutBounds(x, y + (if (headerH > 100f) 64f else 52f) - 6f, width(label, 12f, Theme.FONT_SEMIBOLD) + 44f, 30f)
            ctx.drawShape(pb, ShapeStyle(if (hover(pb)) RED.copy(a = 0.85f) else RED, 15f))
            icon("▶", pb.x + 17f, pb.y + 15f, 11f, Theme.TEXT_PRIMARY)
            text(label, pb.x + 30f, pb.y + 9f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
            hits += pb to { MusicPlayer.playQueue(tracks, if (MusicPlayer.shuffle) (tracks.indices).random() else 0) }
            if (headerH < 100f) headerH = 92f
        }

        // 右上角：列表 / 封面墙
        val seg = LayoutBounds(area.right - segW, y + 4f, segW, 26f)
        card(seg, Theme.CARD, 13f)
        val half = LayoutBounds(seg.x, seg.y, segW / 2f, seg.height)
        val other = LayoutBounds(seg.x + segW / 2f, seg.y, segW / 2f, seg.height)
        ctx.drawGlass(if (coverView) other else half, Theme.GLASS_SELECTION)
        text("List", half.x + (half.width - width("List", 12f, Theme.FONT_MEDIUM)) / 2f, half.y + 8f, 12f,
            if (coverView) Theme.TEXT_SECONDARY else Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        text("Covers", other.x + (other.width - width("Covers", 12f, Theme.FONT_MEDIUM)) / 2f, other.y + 8f, 12f,
            if (coverView) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        hits += half to { if (coverView) toggleCoverView() }
        hits += other to { if (!coverView) toggleCoverView() }

        // 定位到正在播放（列表里有这首歌时才显示）
        var rightX = seg.x - 10f
        if (currentInList()) {
            val label = "Locate"
            val lw = width(label, 11f, Theme.FONT_MEDIUM) + 34f
            val lb = LayoutBounds(rightX - lw, y + 4f, lw, 26f)
            card(lb, if (hover(lb)) Theme.CARD_HOVER else Theme.CARD, 13f)
            icon("◎", lb.x + 14f, lb.y + 13f, 11f, RED)
            text(label, lb.x + 25f, lb.y + 8f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            hits += lb to { locateCurrent() }
            rightX = lb.x - 10f
        }

        // 歌单内筛选框（按歌名 / 歌手，输入即筛选）
        if (tracks != null && page != Page.SEARCH) {
            val fw = 190f
            filterRect = LayoutBounds(rightX - fw, y + 4f, fw, 26f)
            card(filterRect, if (filterFocused) Theme.CARD_HOVER else Theme.CARD, 13f)
            if (filterFocused) ctx.drawShape(filterRect, ShapeStyle(Color(0f, 0f, 0f, 0f), 13f, 1f, RED))
            ctx.drawShape(LayoutBounds(filterRect.x + 10f, filterRect.y + 8f, 8f, 8f), ShapeStyle(Color(0f, 0f, 0f, 0f), 4f, 1.3f, Theme.TEXT_SECONDARY))
            ctx.drawShape(LayoutBounds(filterRect.x + 16.5f, filterRect.y + 15f, 4f, 1.5f), ShapeStyle(Theme.TEXT_SECONDARY, 0.75f))
            val caret = if (filterFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "|" else ""
            val placeholder = filterText.isEmpty() && !filterFocused
            var shownText = if (placeholder) "Filter this list..." else filterText + caret
            val maxW = filterRect.width - 30f - (if (filterText.isNotEmpty()) 18f else 0f)
            while (shownText.length > 1 && width(shownText, 11f) > maxW) shownText = shownText.substring(1)
            text(shownText, filterRect.x + 26f, filterRect.y + 8f, 11f, if (placeholder) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY)
            hits += filterRect to { filterFocused = true; searchFocused = false }
            if (filterText.isNotEmpty()) {
                val xb = LayoutBounds(filterRect.right - 22f, filterRect.y + 4f, 18f, 18f)
                if (hover(xb)) ctx.drawShape(xb, ShapeStyle(Theme.CARD_HOVER, 9f))
                icon("×", xb.x + 9f, xb.y + 9f, 11f, Theme.TEXT_SECONDARY)
                hits.add(0, xb to { clearFilter() })     // 优先于筛选框本身
            }
        } else {
            filterRect = LayoutBounds()
        }

        }
        contentList = LayoutBounds(area.x, y + headerH, area.width, area.bottom - (y + headerH))
        if (tracks == null) return
        val list = shown(tracks)
        if (list.isEmpty() && filterText.isNotEmpty()) {
            text("No songs match \"$filterText\"", contentList.x + 12f, contentList.y + 20f, 12f, Theme.TEXT_TERTIARY)
            return
        }
        if (coverView) itemAnim(0) { drawCoverflow(contentList, tracks, list) } else drawTrackList(contentList, tracks, list)
    }

    private fun currentTracks(): Pair<String, List<NeteaseTrack>?> = when (page) {
        Page.SEARCH -> "\"$lastQuery\"" to searchResults
        Page.QUEUE -> "Now Playing" to MusicPlayer.queue
        Page.PLAYLIST -> {
            val p = opened
            (p?.name ?: "") to (if (p != null) tracksCache[p.id] else null)
        }
        else -> "" to null
    }

    // ---------------------------------------------------------------- 歌单内筛选 / 定位

    private fun norm(s: String) = s.lowercase().replace(" ", "")

    /** 当前页显示的歌（筛选后），带原来的序号；结果缓存起来，两千多首也不卡 */
    private fun shown(all: List<NeteaseTrack>): List<Pair<Int, NeteaseTrack>> {
        val key = norm(filterText)
        if (all === filterCacheSrc && key == filterCacheKey) return filterCache
        filterCacheSrc = all
        filterCacheKey = key
        filterCache = if (key.isEmpty()) {
            all.mapIndexed { i, t -> i to t }
        } else {
            all.mapIndexedNotNull { i, t ->
                if (norm(t.name).contains(key) || t.artists.any { norm(it).contains(key) }) i to t else null
            }
        }
        return filterCache
    }

    private fun clearFilter() {
        filterText = ""
        filterFocused = false
    }

    /** 播放：队列始终是完整歌单（筛选只影响显示），从点的那首开始 */
    private fun playFrom(all: List<NeteaseTrack>, index: Int) {
        if (page == Page.QUEUE) MusicPlayer.playIndex(index) else MusicPlayer.playQueue(all, index)
    }

    /** 跳到正在播放的那首：列表滚到它居中，封面墙转到它，并闪一下 */
    private fun locateCurrent() {
        val all = currentTracks().second ?: return
        val cur = MusicPlayer.current ?: return
        var list = shown(all)
        var idx = list.indexOfFirst { it.second.id == cur.id }
        if (idx < 0 && filterText.isNotEmpty()) {
            // 被筛选掉了：清掉筛选再找
            clearFilter()
            list = shown(all)
            idx = list.indexOfFirst { it.second.id == cur.id }
        }
        if (idx < 0) return
        rightScroll = (idx - listRows / 2).coerceAtLeast(0)
        cfTarget = idx
        cfPos = idx.toFloat()
        flashId = cur.id
        flashAt = System.currentTimeMillis()
    }

    private fun currentInList(): Boolean {
        val cur = MusicPlayer.current ?: return false
        return currentTracks().second?.any { it.id == cur.id } == true
    }

    /** 列表：序号 / 小封面 / 歌名 / 歌手 / 时长 */
    /** 滚动条滑块的位置和高度 */
    private fun thumbOf(track: LayoutBounds, total: Int, rows: Int): LayoutBounds {
        val h = max(28f, track.height * rows / total.coerceAtLeast(1))
        val maxScroll = max(1, total - rows)
        val pos = if (sbDragging) rightScroll.toFloat() else listPos
        val y = track.y + (track.height - h) * (pos / maxScroll).coerceIn(0f, 1f)
        return LayoutBounds(track.x, y, track.width, h)
    }

    /**
     * 歌单右边的滚动条：拖滑块快速滚动，点空白处直接跳过去；
     * 拖动时旁边显示现在在第几首。
     */
    private fun drawScrollbar(track: LayoutBounds, total: Int, rows: Int) {
        sbTrack = track
        sbTotal = total
        if (total <= rows) {
            sbTrack = LayoutBounds()
            return
        }
        val hot = sbDragging || track.expand(8f, 0f).contains(mx, my)
        ctx.drawShape(track, ShapeStyle(Color(1f, 1f, 1f, if (hot) 0.10f else 0.05f), track.width / 2f))
        val th = thumbOf(track, total, rows)
        val thumb = if (hot) LayoutBounds(th.x - 1.5f, th.y, th.width + 3f, th.height) else th
        ctx.drawShape(thumb, ShapeStyle(if (sbDragging) RED else Color(1f, 1f, 1f, if (hot) 0.55f else 0.30f), thumb.width / 2f))
        if (sbDragging) {
            val label = "${rightScroll + 1} / $total"
            val lw = width(label, 11f, Theme.FONT_SEMIBOLD) + 18f
            val lb = LayoutBounds(track.x - lw - 10f, th.y + th.height / 2f - 12f, lw, 24f)
            ctx.drawGlass(lb, Theme.GLASS_SELECTION)
            text(label, lb.x + 9f, lb.y + 7f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        }
    }

    private fun scrollbarTo(y: Int) {
        val track = sbTrack
        val rows = listRows
        if (sbTotal <= rows || track.height <= 0f) return
        val th = thumbOf(track, sbTotal, rows)
        val frac = ((y - sbGrab) - track.y) / max(1f, track.height - th.height)
        rightScroll = (frac.coerceIn(0f, 1f) * (sbTotal - rows)).toInt()
    }

    private fun drawTrackList(list: LayoutBounds, all: List<NeteaseTrack>, tracks: List<Pair<Int, NeteaseTrack>>) {
        // 表头
        text("#", list.x + 12f, list.y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        text("TITLE", list.x + 84f, list.y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        val sbW = 16f   // 右边留给滚动条
        text("TIME", list.right - sbW - 12f - width("TIME", 10f, Theme.FONT_SEMIBOLD), list.y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        ctx.drawShape(LayoutBounds(list.x, list.y + 16f, list.width - sbW, 1f), ShapeStyle(Theme.SEPARATOR))
        val area = LayoutBounds(list.x, list.y + 22f, list.width - sbW, list.height - 22f)

        val rowH = 44f
        val rows = max(1, (area.height / rowH).toInt())
        listRows = rows
        rightScroll = rightScroll.coerceIn(0, max(0, tracks.size - rows))
        listPos = if (sbDragging) rightScroll.toFloat() else easeScroll(listPos, rightScroll, rows * 3)
        drawScrollbar(LayoutBounds(list.right - 9f, area.y + 2f, 5f, rows * rowH - 8f), tracks.size, rows)
        val flash = (1f - (System.currentTimeMillis() - flashAt) / 1400f).coerceIn(0f, 1f)
        val playing = MusicPlayer.current
        val first = kotlin.math.floor(listPos).toInt()
        val frac = listPos - first
        val clipArea = LayoutBounds(area.x - 4f, area.y - 2f, area.width + 8f, rows * rowH)
        beginClip(clipArea)
        for (r in 0..rows) {
            val vi = first + r
            if (vi < 0) continue
            if (vi >= tracks.size) break
            val (i, t) = tracks[vi]
            itemAnim(r) {
            val row = LayoutBounds(area.x, area.y + (r - frac) * rowH, area.width, rowH - 4f)
            val isCur = playing != null && playing.id == t.id
            when {
                isCur -> ctx.drawGlass(row, Theme.GLASS_SELECTION)
                hover(row) -> card(row, Theme.CARD_HOVER, 10f)
            }
            if (flash > 0f && t.id == flashId) {
                ctx.drawShape(row, ShapeStyle(Color(0f, 0f, 0f, 0f), 10f, 1.5f, RED.copy(a = flash)))
            }
            if (isCur && MusicPlayer.state == MusicPlayer.State.PLAYING) {
                icon("♫", row.x + 18f, row.y + 20f, 12f, RED)
            } else {
                text("${i + 1}", row.x + 12f, row.y + 15f, 11f, Theme.TEXT_TERTIARY)
            }
            val thumb = LayoutBounds(row.x + 46f, row.y + 4f, row.height - 8f, row.height - 8f)
            drawCover(thumb, CoverCache.track(t), 6f)
            val tx = thumb.right + 12f
            val durW = 44f
            val avail = row.right - durW - 12f - tx
            text(trim(t.name, avail, 13f), tx, row.y + 7f, 13f, if (isCur) RED else Theme.TEXT_PRIMARY)
            text(trim(t.artists.joinToString(" / "), avail, 11f), tx, row.y + 23f, 11f, Theme.TEXT_SECONDARY)
            val d = fmt(t.durationMs)
            text(d, row.right - 12f - width(d, 11f), row.y + 15f, 11f, Theme.TEXT_TERTIARY)
            hits += row.intersect(clipArea) to { playFrom(all, i) }
            }
        }
        endClip()
    }

    // ---------------------------------------------------------------- 封面墙（Coverflow）

    private fun focusCurrentInCoverflow() {
        val tracks = currentTracks().second?.let { shown(it) } ?: return
        val cur = MusicPlayer.current
        val idx = if (cur != null) tracks.indexOfFirst { it.second.id == cur.id } else -1
        cfTarget = if (idx >= 0) idx else 0
        cfPos = cfTarget.toFloat()
    }

    /**
     * 中间一张大封面，两边的依次变小、变淡、往后叠。
     * 滚轮 / ←→ / 点两侧切换，点中间那张（或回车）播放。
     */
    private fun drawCoverflow(area: LayoutBounds, all: List<NeteaseTrack>, shownList: List<Pair<Int, NeteaseTrack>>) {
        val tracks = shownList.map { it.second }
        if (tracks.isEmpty()) return
        cfTarget = cfTarget.coerceIn(0, tracks.size - 1)

        val size = min(area.height * 0.66f, 240f)
        val cx = area.x + area.width / 2f
        // 封面 + 下面三行字作为一个整体，在区域里竖直居中（稍微偏上一点点）
        val blockH = size + 66f
        val cy = area.y + max(8f, (area.height - blockH) * 0.45f) + size / 2f
        val first = max(0, cfTarget - 6)
        val last = min(tracks.size - 1, cfTarget + 6)

        class Item(val i: Int, val d: Float, val b: LayoutBounds)
        val items = (first..last).map { i ->
            val d = i - cfPos
            val ad = abs(d)
            val sign = if (d < 0) -1f else 1f
            val offset = if (ad < 1f) ad * size * 0.62f else size * 0.62f + (ad - 1f) * size * 0.30f
            val scale = 1f - min(ad, 3f) * 0.15f
            val s = size * scale
            Item(i, d, LayoutBounds(cx + sign * offset - s / 2f, cy - s / 2f, s, s))
        }.filter { it.b.right > area.x && it.b.x < area.right }

        // 左右两边：越靠近区域边缘越透明，超出区域的部分裁掉
        val fadeW = area.width * 0.22f
        fun edgeFade(b: LayoutBounds): Float {
            val mid = b.x + b.width / 2f
            val e = (min(mid - area.x, area.right - mid) / fadeW).coerceIn(0f, 1f)
            return e * e * (3f - 2f * e)
        }
        val clip = LayoutBounds(area.x, area.y - 12f, area.width, area.height + 12f)
        beginClip(clip)
        for (item in items.sortedByDescending { abs(it.d) }) {
            val ad = abs(item.d)
            val a = (1f - min(ad, 4f) * 0.2f).coerceAtLeast(0.15f) * edgeFade(item.b)
            if (a < 0.01f) continue
            ctx.pushOpacity(a)
            drawCover(item.b, CoverCache.track(tracks[item.i]), 12f)
            if (ad > 0.5f) card(item.b, Color(0f, 0f, 0f, min(0.45f, ad * 0.18f)), 2f)
            ctx.popOpacity()
        }
        endClip()
        for (item in items.sortedBy { abs(it.d) }) {
            if (edgeFade(item.b) < 0.2f) continue     // 几乎看不见的就不响应点击
            val i = item.i
            hits += item.b.intersect(clip) to {
                if (i == cfTarget) playFrom(all, shownList[i].first) else cfTarget = i
            }
        }

        val t = tracks[cfTarget]
        val isCur = MusicPlayer.current?.id == t.id
        val ty = cy + size / 2f + 14f
        val name = trim(t.name, area.width - 40f, 16f, Theme.FONT_SEMIBOLD)
        text(name, cx - width(name, 16f, Theme.FONT_SEMIBOLD) / 2f, ty, 16f, if (isCur) RED else Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val artist = trim(t.artists.joinToString(" / "), area.width - 40f, 12f)
        text(artist, cx - width(artist, 12f) / 2f, ty + 21f, 12f, Theme.TEXT_SECONDARY)
        val idx = "${cfTarget + 1} / ${tracks.size}"
        text(idx, cx - width(idx, 11f) / 2f, ty + 38f, 11f, Theme.TEXT_TERTIARY)
    }

    // ---------------------------------------------------------------- 播放栏

    private fun drawBar(bar: LayoutBounds) {
        card(bar, Theme.CARD, 16f)
        val t = MusicPlayer.current

        // 左：封面 + 歌名 / 歌手
        val cs = 48f
        val coverBox = LayoutBounds(bar.x + 13f, bar.y + (bar.height - cs) / 2f, cs, cs)
        drawCover(coverBox, t?.let { CoverCache.track(it) }, 8f)
        val infoX = coverBox.right + 12f
        val infoW = 220f
        val title = t?.name ?: "Not playing"
        text(trim(title, infoW, 14f, Theme.FONT_SEMIBOLD), infoX, coverBox.y + 9f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val sub = when {
            t == null -> "-"
            MusicPlayer.message.isNotEmpty() -> MusicPlayer.message
            else -> t.artists.joinToString(" / ")
        }
        text(trim(sub, infoW, 11f), infoX, coverBox.y + 28f, 11f, Theme.TEXT_SECONDARY)

        // 中：上一首 / 播放 / 下一首 / 队列
        val cx = bar.x + bar.width / 2f
        val cy = bar.y + 25f
        fun btn(b: LayoutBounds) {
            if (hover(b)) ctx.drawShape(b, ShapeStyle(Theme.CARD_HOVER, b.height / 2f))
        }
        val playB = LayoutBounds(cx - 17f, cy - 17f, 34f, 34f)
        val prevB = LayoutBounds(cx - 58f - 14f, cy - 14f, 28f, 28f)
        val nextB = LayoutBounds(cx + 58f - 14f, cy - 14f, 28f, 28f)
        val queueB = LayoutBounds(cx + 112f - 14f, cy - 14f, 28f, 28f)
        btn(prevB); btn(playB); btn(nextB); btn(queueB)
        if (MusicPlayer.state == MusicPlayer.State.PLAYING) {
            card(LayoutBounds(cx - 6f, cy - 8f, 4f, 16f), Theme.TEXT_PRIMARY, 1.5f)
            card(LayoutBounds(cx + 2f, cy - 8f, 4f, 16f), Theme.TEXT_PRIMARY, 1.5f)
        } else {
            icon("▶", cx + 1.5f, cy, 18f, Theme.TEXT_PRIMARY)
        }
        // ⏮ = 竖条 + ◀，⏭ = ▶ + 竖条
        card(LayoutBounds(prevB.x + 8f, cy - 5.5f, 2.2f, 11f), Theme.TEXT_PRIMARY, 1f)
        icon("◀", prevB.x + 16.5f, cy, 12f, Theme.TEXT_PRIMARY)
        icon("▶", nextB.x + 11.5f, cy, 12f, Theme.TEXT_PRIMARY)
        card(LayoutBounds(nextB.right - 10.2f, cy - 5.5f, 2.2f, 11f), Theme.TEXT_PRIMARY, 1f)
        icon("≡", queueB.x + 14f, cy, 14f, if (page == Page.QUEUE) RED else Theme.TEXT_PRIMARY)
        hits += prevB to { MusicPlayer.prev() }
        hits += playB to { MusicPlayer.togglePause() }
        hits += nextB to { MusicPlayer.next() }
        hits += queueB to { if (page == Page.QUEUE) goBackOrHome() else go(Page.QUEUE) }

        // 进度条：时间 | 条 | 时间
        val dur = MusicPlayer.durationMs
        val pos = MusicPlayer.positionMs
        val pw = min(440f, bar.width * 0.4f)
        progRect = LayoutBounds(cx - pw / 2f, bar.y + 54f, pw, 4f)
        val l = fmt(pos)
        val rgt = fmt(dur)
        text(l, progRect.x - 10f - width(l, 10f), progRect.y - 3f, 10f, Theme.TEXT_SECONDARY)
        text(rgt, progRect.right + 10f, progRect.y - 3f, 10f, Theme.TEXT_SECONDARY)
        card(progRect, Theme.TRACK, 2f)
        if (dur > 0) {
            val f = (pos.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            card(LayoutBounds(progRect.x, progRect.y, max(4f, progRect.width * f), progRect.height), RED, 2f)
            if (progRect.expand(0f, 6f).contains(mx, my)) {
                val kx = progRect.x + progRect.width * f
                ctx.drawShape(LayoutBounds(kx - 5f, progRect.y - 3f, 10f, 10f), ShapeStyle(Theme.TEXT_PRIMARY, 5f))
            }
        }

        // 右：随机 + 音量
        val vy = bar.y + bar.height / 2f
        volRect = LayoutBounds(bar.right - 30f - 110f, vy - 2f, 110f, 4f)
        speaker(volRect.x - 22f, vy, false)
        speaker(volRect.right + 9f, vy, true)
        card(volRect, Theme.TRACK, 2f)
        card(LayoutBounds(volRect.x, volRect.y, max(4f, volRect.width * MusicPlayer.volume), volRect.height), RED, 2f)
        val knobX = volRect.x + volRect.width * MusicPlayer.volume
        ctx.drawShape(LayoutBounds(knobX - 5f, volRect.y - 3f, 10f, 10f), ShapeStyle(Theme.TEXT_PRIMARY, 5f))

        val shuffleLabel = "Shuffle"
        val sw = width(shuffleLabel, 11f, Theme.FONT_MEDIUM) + 36f
        val shB = LayoutBounds(volRect.x - 40f - sw, vy - 13f, sw, 26f)
        card(shB, if (MusicPlayer.shuffle) RED.copy(a = 0.85f) else if (hover(shB)) Theme.CARD_HOVER else Color(1f, 1f, 1f, 0.07f), 9f)
        icon("⇄", shB.x + 13f, vy, 11f, Theme.TEXT_PRIMARY)
        text(shuffleLabel, shB.x + 24f, vy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        hits += shB to { module.toggleShuffle() }
    }

    private fun goBackOrHome() {
        if (backPage != null) goBack() else go(Page.HOME)
    }

    /** 小喇叭图标：方块 + 喇叭口，loud = 右边再加两道声波 */
    private fun speaker(x: Float, cy: Float, loud: Boolean) {
        card(LayoutBounds(x, cy - 2.5f, 3.5f, 5f), Theme.TEXT_SECONDARY, 1f)
        card(LayoutBounds(x + 3f, cy - 5f, 3.5f, 10f), Theme.TEXT_SECONDARY, 1.2f)
        if (loud) {
            card(LayoutBounds(x + 8.5f, cy - 2.5f, 1.4f, 5f), Theme.TEXT_SECONDARY, 0.7f)
            card(LayoutBounds(x + 11.5f, cy - 4.5f, 1.4f, 9f), Theme.TEXT_SECONDARY, 0.7f)
        }
    }

    // ---------------------------------------------------------------- 扫码登录弹窗

    private fun openLogin() {
        showLogin = true
        NeteaseLogin.start()
    }

    private fun closeLogin() {
        showLogin = false
        NeteaseLogin.cancel()
    }

    private fun uploadQr() {
        val img = NeteaseLogin.qrImage ?: return
        if (NeteaseLogin.qrVersion == qrUploaded) return
        qrLoc?.let { mc.textureManager.deleteTexture(it) }
        qrLoc = mc.textureManager.getDynamicTextureLocation("dango_qr", DynamicTexture(img))
        qrUploaded = NeteaseLogin.qrVersion
    }

    private fun drawLogin() {
        loginHits.clear()
        val state = NeteaseLogin.state
        if (state == NeteaseLogin.State.SUCCESS) {
            showLogin = false
            NeteaseLogin.cancel()
            refreshAll()
            return
        }

        ctx.drawShape(LayoutBounds(0f, 0f, lw, lh), ShapeStyle(Color(0f, 0f, 0f, 0.45f)))
        val w = 300f
        val h = 372f
        val box = LayoutBounds((lw - w) / 2f, (lh - h) / 2f, w, h)
        ctx.drawGlass(box, Theme.GLASS_WINDOW)

        text("Log in to NetEase", box.x + 20f, box.y + 20f, 18f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val closeB = LayoutBounds(box.right - 38f, box.y + 14f, 24f, 24f)
        ctx.drawShape(closeB, ShapeStyle(if (hover(closeB)) Theme.CARD_HOVER else Theme.CARD, 12f))
        text("x", closeB.x + 8.5f, closeB.y + 7f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        loginHits += closeB to { closeLogin() }

        val qs = 200f
        val qrBox = LayoutBounds(box.x + (w - qs) / 2f, box.y + 60f, qs, qs)
        ctx.drawShape(qrBox.expand(10f, 10f), ShapeStyle(Color.WHITE, 14f))
        val loc = qrLoc
        val showQr = NeteaseLogin.qrImage != null && loc != null &&
            (state == NeteaseLogin.State.WAITING || state == NeteaseLogin.State.SCANNED || state == NeteaseLogin.State.EXPIRED)
        if (showQr) {
            ctx.pushOpacity(if (state == NeteaseLogin.State.EXPIRED) 0.2f else 1f)
            ctx.drawImage(qrBox, MCResImage(loc!!))
            ctx.popOpacity()
        } else {
            val t = if (state == NeteaseLogin.State.ERROR) "获取二维码失败" else "正在获取二维码…"
            text(t, qrBox.x + (qs - width(t, 13f)) / 2f, qrBox.y + qs / 2f - 6f, 13f, Color(0.33f, 0.33f, 0.33f, 1f))
        }

        val msg = when (state) {
            NeteaseLogin.State.WAITING -> "打开手机网易云音乐 App 扫码"
            NeteaseLogin.State.SCANNED -> "已扫码，请在手机上确认登录"
            NeteaseLogin.State.EXPIRED -> "二维码已过期"
            NeteaseLogin.State.ERROR -> "出错了，请重试"
            else -> "请稍候…"
        }
        val msgColor = when (state) {
            NeteaseLogin.State.SCANNED -> Theme.GREEN
            NeteaseLogin.State.ERROR, NeteaseLogin.State.EXPIRED -> RED
            else -> Theme.TEXT_PRIMARY
        }
        text(msg, box.x + (w - width(msg, 14f)) / 2f, qrBox.bottom + 24f, 14f, msgColor)

        if (state == NeteaseLogin.State.EXPIRED || state == NeteaseLogin.State.ERROR) {
            val label = "Refresh QR"
            val bw = width(label, 12f, Theme.FONT_MEDIUM) + 28f
            val b = LayoutBounds(box.x + (w - bw) / 2f, qrBox.bottom + 48f, bw, 28f)
            ctx.drawShape(b, ShapeStyle(RED, 14f))
            text(label, b.x + 14f, b.y + 9f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
            loginHits += b to { NeteaseLogin.start() }
        }
    }

    // ---------------------------------------------------------------- 输入

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (showLogin) {
            if (mouseButton == 0) loginHits.firstOrNull { it.first.contains(mx, my) }?.second?.invoke()
            return
        }
        // 鼠标侧键 / 右键：返回上一页
        if (mouseButton == 1 || mouseButton == 3) {
            goBack()
            return
        }
        if (mouseButton != 0) return
        // 进度条
        if (progRect.expand(0f, 6f).contains(mx, my)) {
            val dur = MusicPlayer.durationMs
            if (dur > 0) MusicPlayer.seek(((mx - progRect.x) / progRect.width * dur).toLong())
            return
        }
        // 音量
        if (volRect.expand(6f, 8f).contains(mx, my)) {
            setVolumeAt(mx)
            return
        }
        // 歌单滚动条：按在滑块上就拖，按在空白处就把滑块中心跳过去再拖
        if (!coverView && sbTotal > listRows && sbTrack.height > 0f && sbTrack.expand(8f, 0f).contains(mx, my)) {
            val th = thumbOf(sbTrack, sbTotal, listRows)
            sbGrab = if (my >= th.y && my <= th.bottom) my - th.y else th.height / 2f
            sbDragging = true
            scrollbarTo(my)
            return
        }
        val hit = hits.firstOrNull { it.first.contains(mx, my) }
        if (bindingKey != null && (hit == null || hit.first !in keyButtons)) bindingKey = null
        if (editRow != null && (hit == null || hit.first != editRect.intersect(setArea))) commitEdit()
        if (hit == null || hit.first !== searchRect) searchFocused = false
        if (hit == null || hit.first !== filterRect) filterFocused = false
        if (hit == null || hit.first !== codeRect) codeFocused = false
        hit?.second?.invoke()
    }

    override fun mouseClickMove(mouseX: Int, mouseY: Int, clickedMouseButton: Int, timeSinceLastClick: Long) {
        if (sbDragging) {
            // mouseClickMove 给的是 MC 的坐标，这里用自己算的逻辑坐标
            val ly = ((mc.displayHeight - Mouse.getY() - 1) / uiScale).toInt()
            scrollbarTo(ly)
            return
        }
        if (!showLogin && volRect.expand(12f, 12f).contains(mx, my)) setVolumeAt(mx)
    }

    override fun mouseReleased(mouseX: Int, mouseY: Int, state: Int) {
        super.mouseReleased(mouseX, mouseY, state)
        sbDragging = false
        dragValue = null
    }

    private fun setVolumeAt(x: Int) {
        val v = ((x - volRect.x) / volRect.width).coerceIn(0f, 1f)
        module.setVolume(v)
        MusicPlayer.volume = v
    }

    override fun handleMouseInput() {
        super.handleMouseInput()
        val wheel = Mouse.getEventDWheel()
        if (wheel == 0 || showLogin) return
        val lx = (Mouse.getEventX() / uiScale).toInt()
        val ly = ((mc.displayHeight - Mouse.getEventY() - 1) / uiScale).toInt()
        // 按住 Ctrl 滚得快十倍
        val step = (if (wheel > 0) -3 else 3) * (if (GuiScreen.isCtrlKeyDown()) 10 else 1)
        when {
            setArea.contains(lx, ly) ->
                setTarget = (setTarget + (if (wheel > 0) -60f else 60f) * (if (GuiScreen.isCtrlKeyDown()) 4f else 1f)).coerceIn(0f, setMax)
            sideList.contains(lx, ly) -> leftScroll += step
            gridArea.contains(lx, ly) -> gridTarget = (gridTarget + (if (wheel > 0) -120f else 120f)).coerceIn(0f, gridMax)
            contentList.contains(lx, ly) && coverView -> cfTarget += if (wheel > 0) -1 else 1
            contentList.contains(lx, ly) -> rightScroll += step
        }
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (showLogin) {
            if (keyCode == Keyboard.KEY_ESCAPE) closeLogin()
            return
        }
        // 设置页：正在录按键（Esc 取消）
        // 设置页：正在输入数值。Enter 确定，Esc 取消
        if (editRow != null) {
            when {
                keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER -> commitEdit()
                keyCode == Keyboard.KEY_ESCAPE -> editRow = null
                keyCode == Keyboard.KEY_BACK -> if (editText.isNotEmpty()) editText = editText.dropLast(1)
                GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_A -> editText = ""
                (typedChar.isDigit() || typedChar == '.' || typedChar == ',' || typedChar == '-') && editText.length < 10 ->
                    editText += typedChar
            }
            return
        }
        // 设置页：正在录按键。Esc 取消；Backspace / Delete 清空（Open key 不能清空）
        bindingKey?.let { v ->
            when {
                keyCode == Keyboard.KEY_ESCAPE -> {}
                (keyCode == Keyboard.KEY_BACK || keyCode == Keyboard.KEY_DELETE) && v.minRange == 0 -> v.setValue(0)
                keyCode != 0 -> v.setValue(keyCode)
            }
            bindingKey = null
            return
        }
        if (searchFocused) {
            when {
                keyCode == Keyboard.KEY_ESCAPE -> searchFocused = false
                keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER -> doSearch()
                keyCode == Keyboard.KEY_BACK -> if (searchText.isNotEmpty()) searchText = searchText.dropLast(1)
                GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V -> {
                    // 游戏里打不了中文时，可以复制后粘贴进来
                    val clip = GuiScreen.getClipboardString().replace(Regex("[\\r\\n]"), " ")
                    searchText = (searchText + clip).take(80)
                }
                GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_A -> searchText = ""
                ChatAllowedCharacters.isAllowedCharacter(typedChar) -> searchText = (searchText + typedChar).take(80)
            }
            return
        }
        if (codeFocused) {
            when {
                keyCode == Keyboard.KEY_ESCAPE -> codeFocused = false
                keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER -> {
                    codeFocused = false
                    ListenParty.join(codeInput)
                }
                keyCode == Keyboard.KEY_BACK -> if (codeInput.isNotEmpty()) codeInput = codeInput.dropLast(1)
                GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V ->
                    codeInput = GuiScreen.getClipboardString().filter { it.isLetterOrDigit() }.uppercase().take(8)
                typedChar.isLetterOrDigit() && typedChar.code < 128 -> codeInput = (codeInput + typedChar.uppercaseChar()).take(8)
            }
            return
        }
        if (filterFocused) {
            when {
                keyCode == Keyboard.KEY_ESCAPE -> clearFilter()
                keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER -> filterFocused = false
                keyCode == Keyboard.KEY_BACK -> if (filterText.isNotEmpty()) filterText = filterText.dropLast(1)
                GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V -> {
                    val clip = GuiScreen.getClipboardString().replace(Regex("[\\r\\n]"), " ")
                    filterText = (filterText + clip).take(60)
                }
                GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_A -> filterText = ""
                ChatAllowedCharacters.isAllowedCharacter(typedChar) -> filterText = (filterText + typedChar).take(60)
            }
            rightScroll = 0
            cfTarget = 0
            cfPos = 0f
            return
        }
        // 自己设的快捷键在播放器里也能用
        if (keyCode != Keyboard.KEY_ESCAPE && keyCode != module.openKey.value && module.handleHotkey(keyCode)) return
        val tracksPage = page == Page.PLAYLIST || page == Page.SEARCH || page == Page.QUEUE
        when {
            // 歌单页：Ctrl+F 在歌单里筛选，L 定位到正在播放；其它页面 Ctrl+F 是全网搜索
            tracksPage && page != Page.SEARCH && GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_F -> filterFocused = true
            tracksPage && keyCode == Keyboard.KEY_L -> locateCurrent()
            tracksPage && !coverView && keyCode == Keyboard.KEY_HOME -> rightScroll = 0
            tracksPage && !coverView && keyCode == Keyboard.KEY_END -> rightScroll = Int.MAX_VALUE / 2
            tracksPage && !coverView && keyCode == Keyboard.KEY_PRIOR -> rightScroll -= listRows
            tracksPage && !coverView && keyCode == Keyboard.KEY_NEXT -> rightScroll += listRows
            GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_F -> searchFocused = true
            keyCode == Keyboard.KEY_SPACE -> MusicPlayer.togglePause()
            keyCode == Keyboard.KEY_BACK -> goBack()
            tracksPage && coverView && keyCode == Keyboard.KEY_RIGHT -> cfTarget++
            tracksPage && coverView && keyCode == Keyboard.KEY_LEFT -> cfTarget--
            tracksPage && coverView && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) -> {
                val all = currentTracks().second
                if (all != null) {
                    val list = shown(all)
                    if (cfTarget in list.indices) playFrom(all, list[cfTarget].first)
                }
            }
            keyCode == Keyboard.KEY_RIGHT -> MusicPlayer.seek(MusicPlayer.positionMs + 5000)
            keyCode == Keyboard.KEY_LEFT -> MusicPlayer.seek(MusicPlayer.positionMs - 5000)
            keyCode == Keyboard.KEY_ESCAPE -> close()
            // 按快捷键打开时，同一次按键事件会紧接着传进这个界面，刚打开的一小会儿里不能把它当成"关闭"
            keyCode == module.openKey.value && System.currentTimeMillis() - openedAt > 300L -> close()
        }
    }
}
