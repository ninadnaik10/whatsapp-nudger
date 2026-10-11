package com.hackday.notifrouter.service

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.hackday.notifrouter.data.MessageEntity
import java.util.concurrent.ConcurrentHashMap

/** Opens the WhatsApp chat a message came from. */
object ChatLauncher {
    private const val TAG = "ChatLauncher"

    /** Latest notification tap-action per conversation; only lives as long as our process. */
    private val tapIntents = ConcurrentHashMap<String, PendingIntent>()

    fun remember(conversation: String, intent: PendingIntent?) {
        if (intent != null) tapIntents[conversation] = intent
    }

    /**
     * 1. WhatsApp's own per-chat shortcut target: `<pkg>/com.whatsapp.Conversation` with a `jid`
     *    extra (exported, it's what WhatsApp's launcher shortcuts use) — works for groups too.
     * 2. The original notification's tap action, if we saw it in this process.
     * 3. Just launch WhatsApp.
     */
    fun open(context: Context, m: MessageEntity) {
        m.chatId?.let { jid ->
            val intent = Intent(Intent.ACTION_VIEW)
                .setComponent(ComponentName(m.packageName, "com.whatsapp.Conversation"))
                .putExtra("jid", jid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Conversation activity not found", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Conversation activity not accessible", e)
            }
        }
        tapIntents[m.conversation]?.let { pi ->
            try {
                val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                } else null
                pi.send(context, 0, null, null, null, null, options)
                return
            } catch (e: PendingIntent.CanceledException) {
                tapIntents.remove(m.conversation)
            }
        }
        val launch = context.packageManager.getLaunchIntentForPackage(m.packageName)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Toast.makeText(context, "WhatsApp isn't installed", Toast.LENGTH_SHORT).show()
        }
    }
}
