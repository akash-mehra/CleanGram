package com.example

/** Keep IME space and navigation space separate; fullscreen never inherits an IME gap. */
object ShellLayout {
    data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int, val showDock: Boolean)
    fun insets(left: Int, top: Int, right: Int, navigationBottom: Int, imeBottom: Int, keyboard: Boolean, fullscreen: Boolean) =
        Insets(left, top, right, if (fullscreen) navigationBottom else maxOf(navigationBottom, imeBottom), !keyboard && !fullscreen)
}
