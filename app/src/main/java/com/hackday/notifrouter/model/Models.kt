package com.hackday.notifrouter.model

/** Routing actions — same contract as the HackerRank Orchestrate dataset. */
enum class Action(val wire: String, val rank: Int) {
    NOTIFY("notify", 2),
    DIGEST("digest", 1),
    MUTE("mute", 0);

    companion object {
        fun from(value: String?): Action? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

enum class MessageType(val wire: String) {
    PERSONAL("personal"),
    URGENT("urgent"),
    EVENT("event"),
    PAYMENT("payment"),
    BUSINESS_UPDATE("business_update"),
    PROMOTION("promotion"),
    GREETING("greeting"),
    FORWARD("forward"),
    SPAM("spam"),
    SCAM("scam"),
    UNKNOWN("unknown");

    companion object {
        fun from(value: String?): MessageType = entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: UNKNOWN
    }
}

enum class MediaType(val wire: String) {
    NONE("none"), IMAGE("image"), VOICE("voice"), VIDEO("video"), DOCUMENT("document"), STICKER("sticker"), OTHER("other");

    /** Media whose picture WhatsApp can attach to the notification. */
    val isVisual: Boolean get() = this == IMAGE || this == STICKER
}

/** What happened to a message's image on its way to Gemma. */
enum class ImageStatus(val wire: String, val label: String) {
    NONE("none", ""),
    READ("read", "image read from notification"),
    SENT_TO_GEMMA("sent_to_gemma", "Gemma saw the image"),
    /** The text says it's a photo, but the notification carried no image data. */
    MISSING("missing", "Image not included in notification"),
    /** The notification had an image URI but the sending app didn't grant us read access. */
    DENIED("denied", "Image not readable (access denied)"),
}

/** One incoming chat message extracted from a StatusBarNotification. */
data class ParsedNotification(
    val sbnKey: String,
    val packageName: String,
    val postTime: Long,
    val messageTime: Long,
    val isGroup: Boolean,
    /** Group name for group chats, otherwise the sender's display name. */
    val conversation: String,
    val sender: String,
    val text: String,
    val mediaType: MediaType,
    /** null when READ_CONTACTS isn't granted. */
    val senderIsContact: Boolean?,
    val senderLooksLikePhoneNumber: Boolean,
    val hasReplyAction: Boolean,
    val category: String?,
    val channelId: String?,
    /** JPEG bytes when the notification carried an image (MessagingStyle data URI or EXTRA_PICTURE). */
    val imageBytes: ByteArray? = null,
    val imageStatus: ImageStatus = if (imageBytes != null) ImageStatus.READ else ImageStatus.NONE,
    /** WhatsApp chat JID (e.g. "…@s.whatsapp.net" / "…@g.us"), from the notification's conversation shortcut. */
    val chatId: String? = null,
) {
    val dedupKey: String
        get() = "$packageName|$conversation|$sender|$messageTime|${text.hashCode()}"

    // ByteArray breaks data-class equality; compare on identity fields only.
    override fun equals(other: Any?): Boolean = other is ParsedNotification && other.dedupKey == dedupKey
    override fun hashCode(): Int = dedupKey.hashCode()
}

data class RoutingDecision(
    val action: Action,
    val messageType: MessageType,
    val reason: String,
    val confidence: Float,
    val evidenceIds: List<Long>,
    /** rule | llm | fallback */
    val source: String,
    /** True when the image was actually passed to Gemma for this decision. */
    val imageUsed: Boolean = false,
)
