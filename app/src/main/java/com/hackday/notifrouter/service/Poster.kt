package com.hackday.notifrouter.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hackday.notifrouter.R
import com.hackday.notifrouter.data.MessageEntity
import com.hackday.notifrouter.model.ParsedNotification
import com.hackday.notifrouter.ui.MainActivity

/** Our own notifications: re-posted important messages (strict mode) and the digest summary. */
class Poster(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    init {
        val sys = context.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannel(
            NotificationChannel(CHANNEL_IMPORTANT, "Important messages", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Messages Gemma decided are worth interrupting you for" }
        )
        sys.createNotificationChannel(
            NotificationChannel(CHANNEL_DIGEST, "Digest", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Silent summary of messages held for later" }
        )
    }

    private fun canPost() = nm.areNotificationsEnabled() && (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Tagged "msg:<id>" so the listener can attribute taps/dismissals back to the DB row. */
    @SuppressLint("MissingPermission") // checked in canPost()
    fun postImportant(id: Long, m: ParsedNotification, original: PendingIntent?, senderIcon: Icon?) {
        if (!canPost()) return
        val title = if (m.isGroup) "${m.sender} @ ${m.conversation}" else m.sender
        // Keep WhatsApp's photo preview: we replaced its notification, so we must show the image ourselves.
        val picture = m.imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val style = if (picture != null) {
            NotificationCompat.BigPictureStyle().bigPicture(picture)
        } else {
            NotificationCompat.BigTextStyle().bigText(m.text)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_IMPORTANT)
            .setSmallIcon(R.drawable.ic_stat_router)
            .setLargeIcon(senderIcon)
            .setContentTitle(title)
            .setContentText(m.text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(original ?: openApp())
            .setAutoCancel(true)
            // Updating an existing alert (e.g. to add a photo that finished downloading) stays silent.
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(tagFor(id), id.toInt(), n)
    }

    fun cancelImportant(id: Long) = nm.cancel(tagFor(id), id.toInt())

    /** True while our re-posted alert for message [id] is still in the shade. */
    fun isShowing(id: Long): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == tagFor(id) }

    @SuppressLint("MissingPermission") // checked in canPost()
    fun updateDigestSummary(count: Int, latest: List<MessageEntity>) {
        if (!canPost()) return
        if (count == 0) {
            nm.cancel(DIGEST_ID)
            return
        }
        val style = NotificationCompat.InboxStyle().setBigContentTitle("$count messages held for later")
        latest.forEach { style.addLine("${it.sender}: ${it.text.take(60)}") }
        val n = NotificationCompat.Builder(context, CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_router)
            .setContentTitle("Digest")
            .setContentText("$count messages held for later")
            .setStyle(style)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .build()
        nm.notify(DIGEST_ID, n)
    }

    companion object {
        const val CHANNEL_IMPORTANT = "important"
        const val CHANNEL_DIGEST = "digest"
        private const val DIGEST_ID = 1
        fun tagFor(id: Long) = "msg:$id"
        fun idFromTag(tag: String?): Long? =
            if (tag == null || !tag.startsWith("msg:")) null else tag.removePrefix("msg:").toLongOrNull()
    }
}
