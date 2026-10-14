package me.sorihiko.dango.music

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.mc

import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ResourceLocation

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * 多张封面的缓存（封面墙、列表缩略图用）：
 * 后台下载 + 裁成正方形，渲染线程上传成纹理，最多保留 [MAX] 张，最久没用的先删。
 */
object CoverCache {

    private const val MAX = 64
    /**
     * 下载线程：后加的任务先做（屏幕上正在看的封面优先，滚过去的旧请求排后面）
     */
    private val worker = ThreadPoolExecutor(
        3, 3, 0L, TimeUnit.MILLISECONDS,
        object : LinkedBlockingDeque<Runnable>() {
            override fun offer(e: Runnable): Boolean = offerFirst(e)
        },
    ) { r -> Thread(r, "Dango-CoverCache").apply { isDaemon = true } }

    private val requested = ConcurrentHashMap.newKeySet<String>()
    /** 下载失败的时间：过一会儿再重试（以前失败一次就再也不试了，封面会一直空着） */
    private val failedAt = ConcurrentHashMap<String, Long>()
    private const val RETRY_MS = 8000L
    private val failLogs = AtomicInteger()
    private val ready = ConcurrentLinkedQueue<Pair<String, BufferedImage>>()

    // 只在渲染线程访问
    private val textures = object : LinkedHashMap<String, ResourceLocation>(16, 0.75f, true) {}
    /** 已下载的正方形原图（做圆角版本用），和纹理一起淘汰 */
    private val sources = HashMap<String, BufferedImage>()
    private val roundQueued = HashSet<String>()

    /** 圆角半径占边长的比例（Song card / 灵动岛用） */
    private const val ROUND = 0.2f

    /** 渲染线程每帧调用一次：把下载好的图片上传成纹理，超出上限就删掉最久没用的 */
    fun upload() {
        while (true) {
            val (key, img) = ready.poll() ?: break
            val loc = mc.textureManager.getDynamicTextureLocation("dango_cover_c", DynamicTexture(img))
            textures.put(key, loc)?.let { mc.textureManager.deleteTexture(it) }
            if (ROUND_SUFFIX !in key) sources[key] = img
        }
        while (textures.size > MAX) {
            val eldest = textures.entries.iterator().next()
            mc.textureManager.deleteTexture(eldest.value)
            requested.remove(eldest.key)
            roundQueued.remove(eldest.key)
            sources.remove(eldest.key)
            textures.remove(eldest.key)
        }
    }

    /** 取封面；还没有就去下载，先返回 null（画占位） */
    fun get(key: String, url: String): ResourceLocation? {
        textures[key]?.let { return it }
        if (url.isEmpty()) return null
        val failed = failedAt[key]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null
        if (!requested.add(key)) return null
        worker.execute {
            var ok = false
            var why = ""
            try {
                val full = if ("?" in url) url else "$url?param=200y200"
                val bytes = NeteaseApi.request(full, "image/jpeg,image/png,image/*;q=0.8")
                if (bytes == null) {
                    why = "download failed"
                } else {
                    val img = ImageIO.read(ByteArrayInputStream(bytes))
                    if (img == null) {
                        why = "unreadable image (${bytes.size} bytes)"
                    } else {
                        ready.add(key to square(img, 128))
                        failedAt.remove(key)
                        ok = true
                    }
                }
            } catch (e: Throwable) {
                why = e.toString()
            } finally {
                if (!ok) {
                    requested.remove(key)
                    failedAt[key] = System.currentTimeMillis()
                    if (failLogs.incrementAndGet() <= 20) DangoLogger.warn("Cover {} failed: {} ({})", key, why, url)
                }
            }
        }
        return null
    }

    fun track(t: NeteaseTrack) = get("t:${t.id}", t.coverUrl)

    private const val ROUND_SUFFIX = "#r"

    /** 圆角版封面（渲染线程调用）：原图下好后再生成一张四角透明的纹理。ratio = 圆角半径 / 边长 */
    fun rounded(key: String, url: String, ratio: Float = ROUND): ResourceLocation? {
        val rk = key + ROUND_SUFFIX + (ratio * 100).toInt()
        textures[rk]?.let { return it }
        get(key, url)                                  // 确保原图在下载 / 已缓存
        val src = sources[key] ?: return null
        if (roundQueued.add(rk)) {
            worker.execute {
                try {
                    ready.add(rk to roundCorners(src, ratio))
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    fun trackRounded(t: NeteaseTrack, ratio: Float = ROUND) = rounded("t:${t.id}", t.coverUrl, ratio)

    /** 四个角挖成圆角（抗锯齿：边缘按覆盖率给透明度） */
    private fun roundCorners(src: BufferedImage, ratio: Float): BufferedImage {
        val w = src.width
        val h = src.height
        val r = min(w, h) * ratio
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = x + 0.5f
                val py = y + 0.5f
                // 到最近的圆角圆心的距离（不在角落区域时为 0）
                val dx = kotlin.math.max(kotlin.math.max(r - px, px - (w - r)), 0f)
                val dy = kotlin.math.max(kotlin.math.max(r - py, py - (h - r)), 0f)
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                val cover = (r - d + 0.5f).coerceIn(0f, 1f)
                val argb = src.getRGB(x, y)
                val a = (((argb ushr 24) and 0xFF) * cover).toInt()
                out.setRGB(x, y, (a shl 24) or (argb and 0xFFFFFF))
            }
        }
        return out
    }
    fun playlist(p: NeteasePlaylist) = get("p:${p.id}", p.coverUrl)

    private fun square(src: BufferedImage, size: Int): BufferedImage {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(src, 0, 0, size, size, x, y, x + side, y + side, null)
        g.dispose()
        return out
    }
}
