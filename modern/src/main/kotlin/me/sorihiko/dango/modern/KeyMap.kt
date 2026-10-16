package me.sorihiko.dango.modern

import org.lwjgl.glfw.GLFW

/**
 * 统一键码（LWJGL 2 的数值，见 [me.sorihiko.dango.platform.Keys]）和 GLFW 键码互相转换。
 * 设置文件里存的是统一键码，所以 1.8.9 和高版本的快捷键设置可以通用。
 */
object KeyMap {
    private val toGlfw = HashMap<Int, Int>()
    private val fromGlfw = HashMap<Int, Int>()

    private fun map(lwjgl2: Int, glfw: Int) {
        toGlfw[lwjgl2] = glfw
        fromGlfw[glfw] = lwjgl2
    }

    init {
        map(1, GLFW.GLFW_KEY_ESCAPE)
        // 数字键 1-9, 0
        for (i in 0..8) map(2 + i, GLFW.GLFW_KEY_1 + i)
        map(11, GLFW.GLFW_KEY_0)
        map(12, GLFW.GLFW_KEY_MINUS); map(13, GLFW.GLFW_KEY_EQUAL)
        map(14, GLFW.GLFW_KEY_BACKSPACE); map(15, GLFW.GLFW_KEY_TAB)
        "QWERTYUIOP".forEachIndexed { i, c -> map(16 + i, GLFW.GLFW_KEY_A + (c - 'A')) }
        map(26, GLFW.GLFW_KEY_LEFT_BRACKET); map(27, GLFW.GLFW_KEY_RIGHT_BRACKET)
        map(28, GLFW.GLFW_KEY_ENTER); map(29, GLFW.GLFW_KEY_LEFT_CONTROL)
        "ASDFGHJKL".forEachIndexed { i, c -> map(30 + i, GLFW.GLFW_KEY_A + (c - 'A')) }
        map(39, GLFW.GLFW_KEY_SEMICOLON); map(40, GLFW.GLFW_KEY_APOSTROPHE); map(41, GLFW.GLFW_KEY_GRAVE_ACCENT)
        map(42, GLFW.GLFW_KEY_LEFT_SHIFT); map(43, GLFW.GLFW_KEY_BACKSLASH)
        "ZXCVBNM".forEachIndexed { i, c -> map(44 + i, GLFW.GLFW_KEY_A + (c - 'A')) }
        map(51, GLFW.GLFW_KEY_COMMA); map(52, GLFW.GLFW_KEY_PERIOD); map(53, GLFW.GLFW_KEY_SLASH)
        map(54, GLFW.GLFW_KEY_RIGHT_SHIFT); map(55, GLFW.GLFW_KEY_KP_MULTIPLY)
        map(56, GLFW.GLFW_KEY_LEFT_ALT); map(57, GLFW.GLFW_KEY_SPACE); map(58, GLFW.GLFW_KEY_CAPS_LOCK)
        for (i in 0..9) map(59 + i, GLFW.GLFW_KEY_F1 + i)          // F1-F10
        map(69, GLFW.GLFW_KEY_NUM_LOCK); map(70, GLFW.GLFW_KEY_SCROLL_LOCK)
        map(71, GLFW.GLFW_KEY_KP_7); map(72, GLFW.GLFW_KEY_KP_8); map(73, GLFW.GLFW_KEY_KP_9)
        map(74, GLFW.GLFW_KEY_KP_SUBTRACT)
        map(75, GLFW.GLFW_KEY_KP_4); map(76, GLFW.GLFW_KEY_KP_5); map(77, GLFW.GLFW_KEY_KP_6)
        map(78, GLFW.GLFW_KEY_KP_ADD)
        map(79, GLFW.GLFW_KEY_KP_1); map(80, GLFW.GLFW_KEY_KP_2); map(81, GLFW.GLFW_KEY_KP_3)
        map(82, GLFW.GLFW_KEY_KP_0); map(83, GLFW.GLFW_KEY_KP_DECIMAL)
        map(87, GLFW.GLFW_KEY_F11); map(88, GLFW.GLFW_KEY_F12)
        for (i in 0..5) map(100 + i, GLFW.GLFW_KEY_F13 + i)        // F13-F18
        map(113, GLFW.GLFW_KEY_F19)
        map(141, GLFW.GLFW_KEY_KP_EQUAL)
        map(156, GLFW.GLFW_KEY_KP_ENTER); map(157, GLFW.GLFW_KEY_RIGHT_CONTROL)
        map(181, GLFW.GLFW_KEY_KP_DIVIDE); map(183, GLFW.GLFW_KEY_PRINT_SCREEN)
        map(184, GLFW.GLFW_KEY_RIGHT_ALT); map(197, GLFW.GLFW_KEY_PAUSE)
        map(199, GLFW.GLFW_KEY_HOME); map(200, GLFW.GLFW_KEY_UP); map(201, GLFW.GLFW_KEY_PAGE_UP)
        map(203, GLFW.GLFW_KEY_LEFT); map(205, GLFW.GLFW_KEY_RIGHT)
        map(207, GLFW.GLFW_KEY_END); map(208, GLFW.GLFW_KEY_DOWN); map(209, GLFW.GLFW_KEY_PAGE_DOWN)
        map(210, GLFW.GLFW_KEY_INSERT); map(211, GLFW.GLFW_KEY_DELETE)
        map(219, GLFW.GLFW_KEY_LEFT_SUPER); map(220, GLFW.GLFW_KEY_RIGHT_SUPER); map(221, GLFW.GLFW_KEY_MENU)
    }

    /** 统一键码 → GLFW；没有对应的键返回 -1 */
    fun toGlfw(key: Int): Int = toGlfw[key] ?: -1

    /** GLFW → 统一键码；没有对应的键返回 0 */
    fun fromGlfw(glfw: Int): Int = fromGlfw[glfw] ?: 0
}
