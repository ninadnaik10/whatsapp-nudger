package com.hackday.notifrouter.llm

import com.hackday.notifrouter.data.MessageEntity
import com.hackday.notifrouter.data.ReactionCount
import com.hackday.notifrouter.model.ImageStatus
import com.hackday.notifrouter.model.ParsedNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the router knows about one message, packed for the prompt. */
data class RoutingContext(
    val message: ParsedNotification,
    val signals: List<String>,
    val history: List<MessageEntity>,
    val reactions: List<ReactionCount>,
    val corrections: List<ReactionCount>,
    val notifiesToday: Int,
    val inQuietHours: Boolean,
    val now: Long = System.currentTimeMillis(),
)

object PromptBuilder {

    val SYSTEM_PROMPT = """
        You are an on-device notification router for the reader's WhatsApp, talking directly to them. For each incoming message decide:
        - notify: important enough to interrupt them now
        - digest: safe and useful, but can wait for a later summary
        - mute: low-value, repetitive, unwanted, suspicious, scam-like or unsafe

        message_type is one of: personal, urgent, event, payment, business_update, promotion, greeting, forward, spam, scam, unknown.

        Guidelines:
        - Clear scams (KYC/account-blocked or disconnection threats, lottery/prize, unknown sender asking for money, OTP or card details, suspicious links or numbers to call) are always mute + scam, regardless of engagement.
        - Direct personal messages from saved contacts, and anything time-sensitive (deadlines, today's schedule changes, emergencies, direct @mentions) lean notify.
        - Group chatter, good-morning greetings, and mass forwards lean digest, or mute if they usually ignore that chat.
        - Stickers are reactions: digest, or mute if they ignore the chat, unless recent HISTORY in that chat is urgent.
        - Promotions: digest if they have engaged with that sender before, otherwise mute.
        - Legitimate payment reminders from known senders are payment; the same request from an unknown number is suspicious.
        - Personalize with HISTORY: if they usually open or reply to this chat, lean up; if they dismiss it or corrected the decision to mute, lean down. Their correction is the strongest signal.

        Reply with exactly one line and nothing else:
        action|message_type|confidence|evidence_ids|reason
        - confidence: 0.00 to 1.00, honest
        - evidence_ids: comma-separated HISTORY ids that influenced you, or none
        - reason: at most 12 words, addressed to the reader as "you"/"your" (never "the user"). If an image is attached, decide mainly from what it shows and say what it shows in the reason.
    """.trimIndent()

    /** Worked examples sent as prior turns; they pin the output format far more reliably than instructions alone. */
    val FEW_SHOT: List<Pair<String, String>> = listOf(
        """
        MESSAGE
        chat: personal | sender: Priya | saved contact: yes | time: Tue 18:40 | media: none
        text: "are you still coming to pick me up at 7? my phone is about to die"
        signals: urgency_words

        HISTORY (same chat or sender, newest first)
        [12] 2d ago Priya: "reached home" -> notify, you opened
        """.trimIndent() to "notify|personal|0.92|12|Priya needs a time-sensitive answer about pickup.",
        """
        MESSAGE
        chat: personal | sender: +91 70421 88310 | saved contact: no | time: Wed 21:05 | media: none
        text: "Your SBI account will be blocked today. Update PAN now: sbi-kyc-update.in"
        signals: has_link, money_or_account_words, unknown_number, not_in_contacts

        HISTORY (same chat or sender, newest first)
        none
        """.trimIndent() to "mute|scam|0.97|none|Unknown number threatening account block with a link.",
        """
        MESSAGE
        chat: group "Family" | sender: Uncle Ravi | saved contact: yes | time: Sun 07:12 | media: image
        text: "📷 Photo Good morning 🌸"
        signals: greeting

        HISTORY (same chat or sender, newest first)
        [40] 1d ago Uncle Ravi: "Good morning 🙏" -> digest, you dismissed
        [33] 2d ago Uncle Ravi: "📷 Photo" -> digest, you dismissed
        """.trimIndent() to "mute|greeting|0.85|40,33|Daily greeting you usually dismiss.",
    )

    fun buildUserPrompt(ctx: RoutingContext): String = buildString {
        val m = ctx.message
        appendLine("MESSAGE")
        append("chat: ").append(if (m.isGroup) "group \"${m.conversation}\"" else "personal")
        append(" | sender: ").append(m.sender)
        append(" | saved contact: ").append(
            when (m.senderIsContact) { true -> "yes"; false -> "no"; null -> "unknown" }
        )
        append(" | time: ").append(TIME_FMT.format(Date(m.messageTime)))
        append(" | media: ").appendLine(
            when (m.imageStatus) {
                ImageStatus.READ, ImageStatus.SENT_TO_GEMMA -> "${m.mediaType.wire} (attached above; read it)"
                ImageStatus.MISSING, ImageStatus.DENIED -> "${m.mediaType.wire} (not available; only the placeholder text)"
                ImageStatus.NONE -> m.mediaType.wire
            }
        )
        appendLine("text: \"${m.text.take(600)}\"")
        if (ctx.signals.isNotEmpty()) appendLine("signals: ${ctx.signals.joinToString(", ")}")
        appendLine()

        appendLine("USER CONTEXT")
        appendLine("interruptions already today: ${ctx.notifiesToday} | quiet hours now: ${if (ctx.inQuietHours) "yes" else "no"}")
        appendLine()

        appendLine("HISTORY (same chat or sender, newest first)")
        if (ctx.history.isEmpty()) appendLine("none")
        ctx.history.forEach { h ->
            append("[").append(h.id).append("] ").append(ago(ctx.now - h.receivedAt))
            append(" ").append(h.sender).append(": \"").append(h.text.replace('\n', ' ').take(120)).append("\"")
            append(" -> ").append(h.action ?: "?")
            h.reaction?.let { append(", you ").append(it.replace('_', ' ')) }
            h.correction?.let { append(", you corrected to ").append(it) }
            appendLine()
        }
        if (ctx.reactions.isNotEmpty()) {
            appendLine("your reactions in this chat: " + ctx.reactions.joinToString(", ") { "${it.reaction} ${it.n}" })
        }
        if (ctx.corrections.isNotEmpty()) {
            appendLine("your corrections for this chat: " + ctx.corrections.joinToString(", ") { "${it.reaction} ${it.n}" })
        }
    }

    private val TIME_FMT = SimpleDateFormat("EEE HH:mm", Locale.US)

    private fun ago(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 60 -> "${min}m ago"
            min < 60 * 24 -> "${min / 60}h ago"
            else -> "${min / (60 * 24)}d ago"
        }
    }
}
