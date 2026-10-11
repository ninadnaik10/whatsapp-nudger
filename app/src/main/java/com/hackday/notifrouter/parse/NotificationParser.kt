package com.hackday.notifrouter.parse

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.core.os.BundleCompat
import com.hackday.notifrouter.model.ImageStatus
import com.hackday.notifrouter.model.MediaType
import com.hackday.notifrouter.model.ParsedNotification
import java.io.ByteArrayOutputStream

/**
 * Turns a StatusBarNotification into one ParsedNotification per chat message.
 *
 * WhatsApp uses MessagingStyle, so a single notification usually carries every unread
 * message in that chat. The Router dedupes them against the database.
 */
class NotificationParser(private val context: Context) {

    private val contactCache = LruCache<String, Boolean>(256)
    /** Image bytes by content URI, filled by [prefetchImages] before the notification can be cancelled. */
    private val imageCache = LruCache<String, ByteArray>(16)

    /**
     * Reads every image in [sbn] right away. Android revokes our read grant for a notification's
     * URIs once it's cancelled (which strict mode does after deciding), so images must be read the
     * moment the notification is posted, not when it reaches the front of the routing queue.
     */
    fun prefetchImages(sbn: StatusBarNotification) {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification) ?: return
        style.messages.forEach { msg ->
            msg.dataUri?.takeIf { msg.dataMimeType?.startsWith("image/") == true }?.let(::loadImage)
        }
    }

    fun parse(sbn: StatusBarNotification): List<ParsedNotification> {
        val n = sbn.notification
        if (sbn.isOngoing) return emptyList()
        // WhatsApp posts a per-app summary ("5 messages from 3 chats") alongside per-chat notifications.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()

        val extras = n.extras
        val hasReply = n.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)

        fun build(
            isGroup: Boolean, conversation: String, sender: String, rawText: String, time: Long, image: ByteArray?,
            imageUriPresent: Boolean = false,
        ): ParsedNotification {
            val (media, text) = detectMedia(rawText)
            val imageStatus = when {
                image != null -> ImageStatus.READ
                imageUriPresent -> ImageStatus.DENIED
                media.isVisual -> ImageStatus.MISSING
                else -> ImageStatus.NONE
            }
            return ParsedNotification(
                sbnKey = sbn.key,
                packageName = sbn.packageName,
                postTime = sbn.postTime,
                messageTime = time,
                isGroup = isGroup,
                conversation = conversation,
                sender = sender,
                text = text,
                mediaType = if (image != null && media == MediaType.NONE) MediaType.IMAGE else media,
                senderIsContact = isContact(sender),
                senderLooksLikePhoneNumber = PHONE_REGEX.matches(sender.trim()),
                hasReplyAction = hasReply,
                category = n.category,
                channelId = n.channelId,
                imageBytes = image,
                imageStatus = imageStatus,
                // Conversation notifications link WhatsApp's per-chat shortcut, whose id is the chat JID.
                chatId = n.shortcutId?.takeIf { '@' in it },
            )
        }

        if (style != null && style.messages.isNotEmpty()) {
            Log.d(TAG, "${sbn.packageName} ${sbn.key}: ${style.messages.size} message(s), group=${style.isGroupConversation}, chat=${n.shortcutId}")
            val isGroup = style.isGroupConversation
            val title = style.conversationTitle?.toString()
            return style.messages.mapNotNull { msg ->
                val text = msg.text?.toString() ?: return@mapNotNull null
                // MessagingStyle uses a null sender for the device owner's own messages (e.g. inline replies).
                val sender = msg.person?.name?.toString() ?: return@mapNotNull null
                val imageUri = msg.dataUri?.takeIf { msg.dataMimeType?.startsWith("image/") == true }
                val image = imageUri?.let(::loadImage)
                if (msg.dataUri != null || text.startsWith("📷")) {
                    Log.i(TAG, "image: mime=${msg.dataMimeType} uri=${msg.dataUri} bytes=${image?.size}")
                }
                build(isGroup, if (isGroup) title ?: sender else sender, sender, text, msg.timestamp, image, imageUri != null)
            }
        }

        // WhatsApp always uses MessagingStyle for chat messages; anything else from it is a
        // summary ("New messages from 2 chats"), backup, or other system notification.
        if (sbn.packageName in MESSAGING_STYLE_APPS) return emptyList()

        // Fallback for apps that don't use MessagingStyle.
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return emptyList()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString() ?: return emptyList()
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        // WhatsApp's legacy group format is "Group Name: Sender".
        val (conversation, sender) = if (isGroup && ": " in title) {
            title.substringBefore(": ") to title.substringAfter(": ")
        } else {
            title to title
        }
        val picture = BundleCompat.getParcelable(extras, Notification.EXTRA_PICTURE, Bitmap::class.java)
        return listOf(build(isGroup, conversation, sender, text, n.`when`.takeIf { it > 0 } ?: sbn.postTime, picture?.toJpeg()))
    }

    /** WhatsApp's per-app summary, e.g. title "[ Alice ]", text "New messages from 1 chat". */
    fun isMessageSummary(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in MESSAGING_STYLE_APPS || sbn.isOngoing) return false
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        if (NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) != null) return false
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return SUMMARY_REGEX.containsMatchIn(text)
    }

    /** WhatsApp replaces media with an emoji placeholder, e.g. "📷 Photo" or "🎤 Voice message (0:12)". */
    private fun detectMedia(text: String): Pair<MediaType, String> {
        val t = text.trim()
        val type = when {
            t.startsWith("📷") || t.startsWith("🖼") -> MediaType.IMAGE
            t.startsWith("🎤") || t.startsWith("🎙") || t.startsWith("Voice message", ignoreCase = true) -> MediaType.VOICE
            t.startsWith("🎥") || t.startsWith("📹") -> MediaType.VIDEO
            t.startsWith("📄") || t.startsWith("📎") -> MediaType.DOCUMENT
            // WhatsApp: "💟 Sent a sticker" (older builds: "Sticker"); the sticker itself arrives later as a PNG.
            t.startsWith("💟") || t.startsWith("👾") || (t.length < 40 && STICKER_REGEX.containsMatchIn(t)) -> MediaType.STICKER
            else -> MediaType.NONE
        }
        return type to t
    }

    private fun isContact(name: String): Boolean? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        contactCache.get(name)?.let { return it }
        val found = try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?",
                arrayOf(name),
                null,
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed", e)
            false
        }
        contactCache.put(name, found)
        return found
    }

    private fun loadImage(uri: Uri): ByteArray? = imageCache.get(uri.toString()) ?: try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.toJpeg()
            ?.also { imageCache.put(uri.toString(), it) }
    } catch (e: Exception) {
        // The sending app may not grant us read access to its content URI.
        Log.w(TAG, "Can't read notification image $uri: ${e.message}")
        null
    }

    private fun Bitmap.toJpeg(): ByteArray {
        val scaled = if (maxOf(width, height) > MAX_IMAGE_SIDE) {
            val f = MAX_IMAGE_SIDE.toFloat() / maxOf(width, height)
            scale((width * f).toInt(), (height * f).toInt())
        } else this
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    companion object {
        private const val TAG = "NotificationParser"
        private const val MAX_IMAGE_SIDE = 768
        private val STICKER_REGEX = Regex("""\bsticker\b""", RegexOption.IGNORE_CASE)
        private val MESSAGING_STYLE_APPS = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val SUMMARY_REGEX = Regex("""\b\d*\s*new messages?\b|\bmessages? from \d+ chats?\b""", RegexOption.IGNORE_CASE)
        private val PHONE_REGEX = Regex("""^\+?[\d\s\-()]{7,}$""")
    }
}
