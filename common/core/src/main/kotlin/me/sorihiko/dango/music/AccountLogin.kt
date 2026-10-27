package me.sorihiko.dango.music

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

/**
 * 账号登录基类（支持网易云、酷狗、酷狗概念版等平台二维码扫码）。
 * 提供状态维护、二维码图片版本控制与线程管理骨架。
 */
abstract class AccountLogin {

    enum class State { IDLE, LOADING, WAITING, SCANNED, SUCCESS, EXPIRED, ERROR }

    @Volatile
    var state: State = State.IDLE
        protected set

    @Volatile
    var qrImage: BufferedImage? = null
        protected set

    /** 每生成一张新二维码 +1，界面据此重新上传纹理 */
    @Volatile
    var qrVersion: Int = 0
        protected set

    @Volatile
    protected var session: Int = 0

    /** 平台展示名称，如 "NetEase", "KuGou", "KuGou Lite" */
    abstract val platformName: String

    /** 扫码提示信息（展示在登录面板中） */
    open val scanPrompt: String
        get() = "打开手机 App 扫码"

    /** 开始登录流程（如获取二维码、启动轮询） */
    fun start() {
        val my = ++session
        state = State.LOADING
        qrImage = null
        Thread({ run(my) }, "Dango-${javaClass.simpleName}").apply {
            isDaemon = true
            start()
        }
    }

    /** 取消当前登录流程 */
    fun cancel() {
        ++session
        if (state != State.SUCCESS) state = State.IDLE
        qrImage = null
    }

    /** 具体的后台登录/轮询逻辑，由各子类实现 */
    protected abstract fun run(mySession: Int)

    /** 通用 ZXing 二维码图片生成辅助函数 */
    protected fun generateQrImage(text: String, size: Int = 256): BufferedImage {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val img = BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                img.setRGB(x, y, if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return img
    }
}
