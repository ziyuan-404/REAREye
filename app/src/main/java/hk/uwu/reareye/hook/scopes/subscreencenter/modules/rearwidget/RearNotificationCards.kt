package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.widget.RemoteViews

/** In-process bridge to the native card host; NMS remains the source of truth. */
internal object RearNotificationCards {
    const val BUSINESS = "reareye_notification_mirror"
    const val CARD_PREFIX = "__notification_mirror__:"
    var post: ((String, RemoteViews) -> Boolean)? = null
    var remove: ((String) -> Unit)? = null
    var reconcile: ((Set<String>) -> Unit)? = null
    var onReady: (() -> Unit)? = null
}
