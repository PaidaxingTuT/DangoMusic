package me.sorihiko.dango.music

import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.musicui.GridPage
import me.sorihiko.dango.musicui.Library
import me.sorihiko.dango.musicui.LoginDialog
import me.sorihiko.dango.musicui.Page
import me.sorihiko.dango.musicui.PartyPage
import me.sorihiko.dango.musicui.PlayerBar
import me.sorihiko.dango.musicui.RED
import me.sorihiko.dango.musicui.ScreenState
import me.sorihiko.dango.musicui.SettingsPage
import me.sorihiko.dango.musicui.Sidebar
import me.sorihiko.dango.musicui.TracksPage
import me.sorihiko.dango.musicui.Ui
import me.sorihiko.dango.ui.GlassRenderContext
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.glass.GlassBackdrop
import me.sorihiko.dango.utils.Color
import me.sorihiko.dango.utils.render.GlStateSnapshot

import net.minecraft.client.gui.GuiScreen
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20

import kotlin.math.min

/**
 * 内置网易云播放器界面（液态玻璃风格，排版参考 MuoniumPlayer）。
 * 这个类只是"外壳"：打开 / 关闭、每帧的准备工作、页面切换、把鼠标键盘分发给各部分。
 * 具体的东西在 musicui 包里：
 *   [Sidebar] 左侧栏 · [PlayerBar] 底部播放栏 · [LoginDialog] 扫码登录
 *   [GridPage] 主页 / 排行榜歌单墙 · [TracksPage] 歌单 / 搜索 / 队列 · [PartyPage] 一起听 · [SettingsPage] 设置
 *   [Library] 从网易云读来的数据 · [ScreenState] 关掉再打开还保留的界面状态 · [Ui] 绘制工具
 *
 * 菜单字体（Inter）只有拉丁字符，所以中文（歌名、歌手、提示）和图标符号用 Minecraft 自带字体画。
 */
class MusicScreen(
    private val module: NeteaseMusicModule,
    /** 从客户端菜单打开时，关闭后回到菜单 */
    private val parent: GuiScreen? = null,
) : GuiScreen() {

    internal val ui = Ui()
    internal val sidebar = Sidebar(this)
    internal val tracks = TracksPage(this)
    internal val login = LoginDialog(this)
    private val grid = GridPage(this)
    private val party = PartyPage(this)
    private val settings = SettingsPage(this, module)
    private val bar = PlayerBar(this, module)

    private var openedAt = 0L
    private var lastNanos = 0L
    /** 换页（含换歌单 / 设置换标签 / 新搜索 / 列表↔封面墙）就重新播入场动画 */
    private var pageKey = ""

    override fun initGui() {
        Keyboard.enableRepeatEvents(true)
        if (ScreenState.context == null) {
            val context = GlassRenderContext()
            val backdrop = GlassBackdrop()
            context.backdrop = backdrop
            ScreenState.context = context
            ScreenState.backdrop = backdrop
        }
        openedAt = System.currentTimeMillis()
        if (Library.playlists.isEmpty()) Library.reloadPlaylists()
        else if (Library.stale) Library.softRefresh()
        Library.loadRecommend()
    }

    override fun onGuiClosed() {
        Keyboard.enableRepeatEvents(false)
    }

    override fun doesGuiPauseGame() = false

    private fun close() {
        mc.displayGuiScreen(parent)
    }

    // ---------------------------------------------------------------- 导航

    internal fun go(p: Page) {
        tracks.clearFilter()
        ScreenState.page = p
        ScreenState.backPage = null
        tracks.resetScroll()
        grid.resetScroll()
        when (p) {
            Page.HOME -> Library.loadRecommend()
            Page.CHARTS -> Library.loadCharts()
            Page.QUEUE -> if (ScreenState.coverView) tracks.focusCurrentInCoverflow()
            else -> {}
        }
    }

    internal fun goBack() {
        if (ScreenState.page == Page.SETTINGS) {
            ScreenState.page = ScreenState.settingsBack
            return
        }
        val b = ScreenState.backPage ?: return
        ScreenState.page = b
        ScreenState.backPage = null
    }

    internal fun goBackOrHome() {
        if (ScreenState.backPage != null) goBack() else go(Page.HOME)
    }

    internal fun openPlaylist(p: NeteasePlaylist, from: Page?) {
        if (ScreenState.opened?.id != p.id) tracks.clearFilter()
        ScreenState.opened = p
        ScreenState.page = Page.PLAYLIST
        ScreenState.backPage = from
        tracks.resetScroll()
        tracks.resetCoverflow()
        Library.loadTracks(p.id)
    }

    internal fun doSearch() {
        val q = sidebar.searchText.trim()
        if (q.isEmpty()) return
        sidebar.searchFocused = false
        tracks.clearFilter()
        if (ScreenState.page != Page.SEARCH) ScreenState.backPage = ScreenState.page
        ScreenState.page = Page.SEARCH
        tracks.resetScroll()
        tracks.resetCoverflow()
        ScreenState.lastQuery = q
        Library.search(q)
    }

    internal fun toggleSettings() {
        settings.commitEdit()
        blurTextFields()
        settings.bindingKey = null
        if (ScreenState.page == Page.SETTINGS) {
            ScreenState.page = ScreenState.settingsBack
        } else {
            ScreenState.settingsBack = ScreenState.page
            ScreenState.page = Page.SETTINGS
            settings.resetScroll()
        }
    }

    internal fun toggleCoverView() {
        ScreenState.coverView = !ScreenState.coverView
        if (ScreenState.coverView) tracks.focusCurrentInCoverflow()
    }

    /** 取消所有输入框的焦点（搜索、筛选、房间码） */
    internal fun blurTextFields() {
        sidebar.searchFocused = false
        tracks.filterFocused = false
        party.codeFocused = false
    }

    // ---------------------------------------------------------------- 绘制

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val snap = GlStateSnapshot()
        try {
            GL20.glUseProgram(0)
            ScreenState.backdrop?.capture()

            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDisable(GL11.GL_CULL_FACE)

            val scale = min(mc.displayWidth / 1280f, mc.displayHeight / 800f).coerceAtLeast(0.5f)
            ui.scale = scale
            ui.lw = mc.displayWidth / scale
            ui.lh = mc.displayHeight / scale
            ui.mx = (Mouse.getX() / scale).toInt()
            ui.my = ((mc.displayHeight - Mouse.getY() - 1) / scale).toInt()
            val ctx = ui.ctx
            ctx.setMousePos(ui.mx, ui.my)

            val now = System.nanoTime()
            ui.frameDt = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1e6f).coerceAtMost(50f)
            lastNanos = now
            grid.tick()
            tracks.tick()
            settings.tick()

            ui.hits.clear()
            settings.beginFrame()
            ui.mt.clear()
            tracks.beginFrame()
            CoverCache.upload()
            login.uploadQr()

            val alpha = ((System.currentTimeMillis() - openedAt) / 180f).coerceIn(0f, 1f)
            ctx.pushScale(scale, scale, 1f)
            ctx.pushOpacity(alpha)
            ctx.drawShape(LayoutBounds(0f, 0f, ui.lw, ui.lh), ShapeStyle(Color(0f, 0f, 0f, 0.22f)))
            drawWindow(alpha)
            ctx.popOpacity()
            ctx.popTransform()
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            ui.mt.flush(scale)

            if (login.visible) {
                ctx.pushScale(scale, scale, 1f)
                login.draw()
                ctx.popTransform()
                ui.mt.flush(scale)
            }
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            snap.restore()
        }
    }

    private fun drawWindow(alpha: Float) {
        // 几乎铺满屏幕，和 MuoniumPlayer 一样
        val mX = 26f
        val mY = 20f
        val win = LayoutBounds(mX, mY + (1f - alpha) * 12f, ui.lw - mX * 2, ui.lh - mY * 2)
        ui.ctx.drawGlass(win, Theme.GLASS_WINDOW)

        val pad = 10f
        val sideW = 224f
        val barH = 74f
        val side = LayoutBounds(win.x + pad, win.y + pad, sideW, win.height - pad * 2)
        val mainX = side.right + pad + 8f
        val mainW = win.right - pad - 8f - mainX
        val barRect = LayoutBounds(side.right + pad, win.bottom - pad - barH, win.right - pad - (side.right + pad), barH)
        val content = LayoutBounds(mainX, win.y + pad + 14f, mainW, barRect.y - 10f - (win.y + pad + 14f))

        sidebar.draw(side)
        drawContent(content)
        bar.draw(barRect)
    }

    private fun drawContent(area: LayoutBounds) {
        // 底部小字只在有提示（读取失败等）时显示
        val foot = Library.status
        if (foot.isNotEmpty()) ui.text(foot, area.x, area.bottom - 11f, 10f, RED, Theme.FONT_SEMIBOLD)
        val body = LayoutBounds(area.x, area.y, area.width, area.height - (if (foot.isNotEmpty()) 18f else 4f))
        // 滚轮区域：哪一页画了，哪一页才有
        grid.area = LayoutBounds()
        tracks.area = LayoutBounds()
        settings.area = LayoutBounds()

        val s = ScreenState
        val key = "${s.page}|${s.opened?.id}|${s.settingsTab}|${s.coverView}|${s.lastQuery}"
        if (key != pageKey) {
            pageKey = key
            ui.pageAt = System.currentTimeMillis()
            tracks.onPageChanged()
        }

        when (s.page) {
            Page.HOME -> {
                val rec = Library.recommend
                val items = if (rec.isNullOrEmpty()) Library.playlists else rec
                val label = if (rec.isNullOrEmpty()) "Your playlists" else "Recommended playlists"
                grid.draw(body, "Welcome to Dango Music!", "$label · ${items.size}", items, Library.recommendLoading && items.isEmpty())
            }
            Page.CHARTS -> {
                val c = Library.charts ?: emptyList()
                grid.draw(body, "Charts", "${c.size} charts", c, Library.chartsLoading)
            }
            Page.PARTY -> ui.itemAnim(0) { party.draw(body) }
            Page.SETTINGS -> settings.draw(body)
            else -> tracks.draw(body)
        }
    }

    // ---------------------------------------------------------------- 鼠标

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        val mx = ui.mx
        val my = ui.my
        if (login.visible) {
            login.mouseClicked(mx, my, mouseButton)
            return
        }
        // 鼠标侧键 / 右键：返回上一页
        if (mouseButton == 1 || mouseButton == 3) {
            goBack()
            return
        }
        if (mouseButton != 0) return
        if (bar.mouseDown(mx, my)) return
        if (tracks.scrollbarDown(mx, my)) return
        val hit = ui.hits.firstOrNull { it.first.contains(mx, my) }
        settings.onClickElsewhere(hit)
        if (hit == null || hit.first !== sidebar.searchRect) sidebar.searchFocused = false
        if (hit == null || hit.first !== tracks.filterRect) tracks.filterFocused = false
        if (hit == null || hit.first !== party.codeRect) party.codeFocused = false
        hit?.second?.invoke()
    }

    override fun mouseClickMove(mouseX: Int, mouseY: Int, clickedMouseButton: Int, timeSinceLastClick: Long) {
        if (tracks.sbDragging) {
            // mouseClickMove 给的是 MC 的坐标，这里用自己算的逻辑坐标
            val ly = ((mc.displayHeight - Mouse.getY() - 1) / ui.scale).toInt()
            tracks.scrollbarDrag(ly)
            return
        }
        if (!login.visible) bar.mouseDrag(ui.mx, ui.my)
    }

    override fun mouseReleased(mouseX: Int, mouseY: Int, state: Int) {
        super.mouseReleased(mouseX, mouseY, state)
        tracks.scrollbarUp()
        settings.stopDrag()
    }

    override fun handleMouseInput() {
        super.handleMouseInput()
        val wheel = Mouse.getEventDWheel()
        if (wheel == 0 || login.visible) return
        val lx = (Mouse.getEventX() / ui.scale).toInt()
        val ly = ((mc.displayHeight - Mouse.getEventY() - 1) / ui.scale).toInt()
        // 按住 Ctrl 滚得快十倍
        val step = (if (wheel > 0) -3 else 3) * (if (GuiScreen.isCtrlKeyDown()) 10 else 1)
        when {
            settings.area.contains(lx, ly) -> settings.scroll(wheel)
            sidebar.listArea.contains(lx, ly) -> sidebar.scrollBy(step)
            grid.area.contains(lx, ly) -> grid.scroll(wheel)
            tracks.area.contains(lx, ly) -> tracks.scroll(wheel, step)
        }
    }

    // ---------------------------------------------------------------- 键盘

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (login.visible) {
            login.keyTyped(keyCode)
            return
        }
        // 正在输入 / 录按键的地方先拿走按键
        if (settings.keyTyped(typedChar, keyCode)) return
        if (sidebar.keyTyped(typedChar, keyCode)) return
        if (party.keyTyped(typedChar, keyCode)) return
        if (tracks.filterKeyTyped(typedChar, keyCode)) return
        // 自己设的快捷键在播放器里也能用
        if (keyCode != Keyboard.KEY_ESCAPE && keyCode != module.openKey.value && module.handleHotkey(keyCode)) return
        if (tracks.shortcutBefore(keyCode)) return
        when {
            // 其它页面 Ctrl+F 是全网搜索
            GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_F -> sidebar.searchFocused = true
            keyCode == Keyboard.KEY_SPACE -> MusicPlayer.togglePause()
            keyCode == Keyboard.KEY_BACK -> goBack()
            tracks.coverflowKey(keyCode) -> {}
            keyCode == Keyboard.KEY_RIGHT -> MusicPlayer.seek(MusicPlayer.positionMs + 5000)
            keyCode == Keyboard.KEY_LEFT -> MusicPlayer.seek(MusicPlayer.positionMs - 5000)
            keyCode == Keyboard.KEY_ESCAPE -> close()
            // 按快捷键打开时，同一次按键事件会紧接着传进这个界面，刚打开的一小会儿里不能把它当成"关闭"
            keyCode == module.openKey.value && System.currentTimeMillis() - openedAt > 300L -> close()
        }
    }
}
