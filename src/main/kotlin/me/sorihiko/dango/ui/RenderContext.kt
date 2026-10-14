package me.sorihiko.dango.ui

import me.sorihiko.dango.font.UiFont
import me.sorihiko.dango.utils.Color
import me.sorihiko.dango.utils.render.img.UiImage

import org.lwjgl.util.vector.Matrix4f
import org.lwjgl.util.vector.Vector2f

interface RenderContext {
    fun getMouseX(): Int
    fun getMouseY(): Int
    fun isMouseOver(bounds: LayoutBounds): Boolean
    fun drawShape(bounds: LayoutBounds, style: ShapeStyle = ShapeStyle())
    fun drawText(text: String, x: Float, y: Float, style: FontStyle)
    fun drawImage(bounds: LayoutBounds, image: UiImage)

    /**
     * 液态玻璃面板：采样模糊后的背景，边缘带折射/色散/高光。
     * 没有可用背景时（例如 HUD 中）退化为半透明圆角矩形。
     */
    fun drawGlass(bounds: LayoutBounds, style: GlassStyle = GlassStyle())
    fun pushClip(bounds: LayoutBounds)
    fun popClip()
    fun pushOpacity(value: Float)
    fun popOpacity()
    fun getOpacity(): Float
    fun pushTransform(transform: Matrix4f)
    fun popTransform()
    fun getTransform(): Matrix4f
    fun pushTranslate(x: Float, y: Float, z: Float)
    fun pushScale(x: Float, y: Float, z: Float)
    fun pushRotate(angle: Float, x: Float, y: Float, z: Float)
}

data class ShapeStyle(
    val fillColor: Color = Color.TRANSPARENT,
    val radius: Float = 0f,
    val borderWidth: Float = 0f,
    val borderColor: Color = Color.TRANSPARENT,
    val shadow: ShadowStyle = ShadowStyle()
)

data class GlassStyle(
    val radius: Float = 0f,
    /** rgb = 染色, a = 染色强度 */
    val tint: Color = Color(1f, 1f, 1f, 0.05f),
    /** 背景亮度上限，保证白字在亮背景（天空/雪地）上也可读 */
    val maxLuma: Float = 0.35f,
    val saturation: Float = 1.35f,
    /** 0 = 清透, 1 = 磨砂 */
    val frost: Float = 0.6f,
    /** 边缘折射位移（逻辑像素） */
    val refraction: Float = 14f,
    /** 透镜斜面宽度（逻辑像素） */
    val bevel: Float = 16f,
    val dispersion: Float = 0.25f,
    val highlight: Float = 1f,
    val shadow: ShadowStyle = ShadowStyle(),
    /** 没有背景可采样时的退化填充色 */
    val fallback: Color = Color(0.11f, 0.11f, 0.13f, 0.88f),
)

data class FontStyle(
    val font: UiFont,
    val color: Color,
    val fontSize: Float,
)

data class ShadowStyle(
    val color: Color = Color.BLACK,
    val size: Float = 0f,
    val offset: Vector2f = Vector2f(0f, 0f)
)