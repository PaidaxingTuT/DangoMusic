package me.sorihiko.dango.font

import me.sorihiko.dango.mc
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.RenderContext
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color
import me.sorihiko.dango.utils.render.GlCaps

import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20

import kotlin.math.max

/**
 * 中英混排：把一串文字按"拉丁字符 / 其它字符"切成几段，
 *   拉丁段用菜单字体（Inter，跟着液态玻璃一起画），
 *   中日韩和符号段用 [CjkFont]（先记下来，玻璃画完后 [flush] 统一画）。
 * 坐标都是调用方的逻辑坐标，y 是文字顶部。
 */
class MixedText {

    companion object {
        /** 顶部 → 基线：和 Theme.drawText 的大写字高居中方式对齐 */
        private const val BASELINE = 0.39f + 0.727f / 2f
        /** 中文字比同字号的 Inter 显得大一点，稍微缩一下 */
        private const val CJK_SCALE = 0.96f

        private fun isLatin(c: Char) = c.code in 32..126
        fun bold(font: String) = font == Theme.FONT_SEMIBOLD || font == Theme.FONT_DISPLAY
        private fun style(bold: Boolean) = if (bold) CjkFont.BOLD else CjkFont.PLAIN
    }

    private class Run(
        val s: String, val x: Float, val y: Float, val size: Float,
        val r: Float, val g: Float, val b: Float, val a: Float,
        val bold: Boolean, val clip: LayoutBounds?, val centered: Boolean,
    )

    private val runs = ArrayList<Run>()

    /** 当前裁剪区域（逻辑坐标），之后记下的文字都按它裁剪 */
    var clip: LayoutBounds? = null

    /** 整体平移（和 ctx.pushTranslate 同步，做入场动画用）：之后记下的中文段都加上这个偏移 */
    var shiftX = 0f
    var shiftY = 0f

    fun clear() {
        runs.clear()
        clip = null
        shiftX = 0f
        shiftY = 0f
    }

    private inline fun segments(s: String, f: (String, Boolean) -> Unit) {
        var i = 0
        while (i < s.length) {
            val latin = isLatin(s[i])
            var j = i + 1
            while (j < s.length && isLatin(s[j]) == latin) j++
            f(s.substring(i, j), latin)
            i = j
        }
    }

    fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float {
        var w = 0f
        segments(s) { seg, latin ->
            w += if (latin) Theme.textWidth(seg, size, font) else CjkFont.width(seg, size * CJK_SCALE, style(bold(font)))
        }
        return w
    }

    fun text(ctx: RenderContext, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) {
        if (s.isEmpty()) return
        val opacity = ctx.getOpacity()
        var pen = x
        segments(s) { seg, latin ->
            if (latin) {
                Theme.drawText(ctx, seg, pen, y + size * 0.39f, size, font, color)
                pen += Theme.textWidth(seg, size, font)
            } else {
                val cs = size * CJK_SCALE
                runs += Run(seg, pen + shiftX, y + size * BASELINE + shiftY, cs, color.r, color.g, color.b, color.a * opacity, bold(font), clip, false)
                pen += CjkFont.width(seg, cs, style(bold(font)))
            }
        }
    }

    /** 图标符号（⌂ ≡ ♫ ♥ ↻ ⇄ ▶ …），按可见部分居中在 (cx, cy) */
    fun icon(ctx: RenderContext, s: String, cx: Float, cy: Float, size: Float, color: Color, bold: Boolean = false) {
        s.forEach { CjkFont.glyph(it, style(bold)) }
        runs += Run(s, cx + shiftX, cy + shiftY, size, color.r, color.g, color.b, color.a * ctx.getOpacity(), bold, clip, true)
    }

    fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): String {
        if (width(s, size, font) <= maxW) return s
        var end = s.length
        while (end > 0 && width(s.substring(0, end) + "...", size, font) > maxW) end--
        return s.substring(0, end) + "..."
    }

    /** 最多折成两行，第二行放不下就省略 */
    fun wrap2(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): List<String> {
        if (width(s, size, font) <= maxW) return listOf(s)
        var end = 1
        while (end < s.length && width(s.substring(0, end + 1), size, font) <= maxW) end++
        val sp = s.lastIndexOf(' ', end)
        if (sp > end / 2) end = sp
        val first = s.substring(0, end).trimEnd()
        val rest = s.substring(end).trimStart()
        return if (rest.isEmpty()) listOf(first) else listOf(first, trim(rest, maxW, size, font))
    }

    /**
     * 画出记下的中文段。k = 逻辑坐标 → 屏幕像素的缩放。
     * 自己设一个屏幕像素的正交投影，所以在 GUI 和世界渲染阶段都能用。
     */
    fun flush(k: Float) {
        if (runs.isEmpty()) return
        GL20.glUseProgram(0)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, mc.displayWidth.toDouble(), mc.displayHeight.toDouble(), 0.0, 1000.0, 3000.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glTranslatef(0f, 0f, -2000f)
        GL11.glScalef(k, k, 1f)

        // 全部直接设 GL 状态（不走 MC 的状态缓存，缓存可能和实际状态对不上），画完原样恢复
        val caps = GlCaps.save()
        GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit)
        GL11.glDisable(GL11.GL_TEXTURE_2D)                 // 关掉光照贴图那一层，否则字会被压暗
        GL13.glActiveTexture(OpenGlHelper.defaultTexUnit)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_LIGHTING)
        GL11.glDisable(GL11.GL_FOG)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glEnable(GL11.GL_BLEND)
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL14.glBlendEquation(GL14.GL_FUNC_ADD)

        for (r in runs) {
            val c = r.clip
            if (c != null) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST)
                GL11.glScissor(
                    (c.x * k).toInt(),
                    (mc.displayHeight - c.bottom * k).toInt(),
                    max(0, (c.width * k).toInt()),
                    max(0, (c.height * k).toInt()),
                )
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST)
            }
            GL11.glColor4f(r.r, r.g, r.b, r.a.coerceIn(0f, 1f))
            if (r.centered) CjkFont.drawCentered(r.s, r.x, r.y, r.size, style(r.bold))
            else CjkFont.draw(r.s, r.x, r.y, r.size, style(r.bold))
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GlCaps.restore(caps)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GlStateManager.color(1f, 1f, 1f, 1f)
        GlStateManager.bindTexture(0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        runs.clear()
    }
}
