package hk.uwu.reareye.ui.config

/** Shared by the launcher, window manager and settings UI. Zero DPI means system. */
internal object RearAppDefaults {
    const val REGION = "[0.292,0,1,1]"
    const val CORNER_RADIUS = 80f
    const val APP_DPI = 450
    /** Baidu's key geometry is a phone layout; 450dpi makes it too narrow on 680px. */
    const val IME_DPI = 240

    fun contentScale(requestedDpi: Int, primaryDpi: Int): Float =
        if (requestedDpi == 0) 1f
        else (requestedDpi.toFloat() / primaryDpi.coerceAtLeast(1)).coerceIn(.25f, 2f)
}
