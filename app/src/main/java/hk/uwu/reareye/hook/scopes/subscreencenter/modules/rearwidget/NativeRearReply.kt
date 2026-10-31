package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.Activity
import android.app.Dialog
import android.app.KeyguardManager
import android.app.Notification
import android.app.RemoteInput
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import hk.uwu.reareye.hook.support.YLog
import java.lang.ref.WeakReference

internal object NativeRearReply {
    var activity = WeakReference<Activity>(null)
    fun action(notification: Notification): Notification.Action? =
        (notification.actions.orEmpty().toList() + Notification.WearableExtender(notification).actions.orEmpty())
            .firstOrNull { action -> action.actionIntent != null && action.remoteInputs.orEmpty().any {
                it.allowFreeFormInput || !it.choices.isNullOrEmpty()
            } }

    fun open(notification: Notification, stillCurrent: () -> Boolean) {
        val activity = activity.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val action = action(notification) ?: return
        if (activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            Toast.makeText(activity, "请先解锁手机再回复", Toast.LENGTH_SHORT).show(); return
        }
        val input = action.remoteInputs.first { it.allowFreeFormInput || !it.choices.isNullOrEmpty() }
        runCatching {
            val type = activity.classLoader.loadClass("miuix.appcompat.app.AlertDialog\$Builder")
            val builder = type.getConstructor(android.content.Context::class.java).newInstance(activity)
            type.getMethod("setTitle", CharSequence::class.java).invoke(builder, input.label ?: "回复")
            val editor = EditText(activity).apply { hint = input.label ?: "输入回复"; maxLines = 3 }
            if (input.allowFreeFormInput) {
                type.getMethod("setView", android.view.View::class.java).invoke(builder, editor)
                val send = DialogInterface.OnClickListener { _, _ ->
                    val text = editor.text.toString().trim()
                    if (text.isNotEmpty()) send(activity, action, input, text, stillCurrent)
                }
                type.getMethod("setPositiveButton", CharSequence::class.java, DialogInterface.OnClickListener::class.java)
                    .invoke(builder, "发送", send)
            } else {
                val choices = input.choices
                type.getMethod("setItems", Array<CharSequence>::class.java, DialogInterface.OnClickListener::class.java)
                    .invoke(builder, choices, DialogInterface.OnClickListener { _, which ->
                        send(activity, action, input, choices[which], stillCurrent)
                    })
            }
            type.getMethod("setNegativeButton", CharSequence::class.java, DialogInterface.OnClickListener::class.java)
                .invoke(builder, "取消", DialogInterface.OnClickListener { _, _ -> })
            (type.getMethod("create").invoke(builder) as Dialog).show()
        }.onFailure {
            YLog.warn("Native rear reply unavailable: ${it.javaClass.simpleName}")
            Toast.makeText(activity, "当前无法在背屏回复", Toast.LENGTH_SHORT).show()
        }
    }

    private fun send(activity: Activity, action: Notification.Action, input: RemoteInput,
                     text: CharSequence, stillCurrent: () -> Boolean) {
        if (!stillCurrent() || activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            Toast.makeText(activity, "通知已更新，请重新打开回复", Toast.LENGTH_SHORT).show(); return
        }
        runCatching {
            val fill = Intent()
            RemoteInput.addResultsToIntent(action.remoteInputs, fill, Bundle().apply { putCharSequence(input.resultKey, text) })
            RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
            action.actionIntent.send(activity, 0, fill)
        }.onFailure { Toast.makeText(activity, "回复未发出，请重试", Toast.LENGTH_SHORT).show() }
    }
}
