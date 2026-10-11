package com.hackday.notifrouter.router

import com.hackday.notifrouter.data.MessageEntity
import com.hackday.notifrouter.model.Action
import com.hackday.notifrouter.model.MessageType
import com.hackday.notifrouter.model.ParsedNotification
import com.hackday.notifrouter.model.RoutingDecision

/**
 * Cheap, deterministic checks that run before (and instead of, when confident) the LLM.
 * Signals are also passed into the prompt so Gemma doesn't have to rediscover them.
 */
object RuleEngine {
    private val LINK = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private val SHORT_LINK = Regex("""\b(bit\.ly|tinyurl\.com|t\.co|goo\.gl|cutt\.ly|rb\.gy|is\.gd|shorturl\.at)/""", RegexOption.IGNORE_CASE)
    private val MONEY = Regex("""\b(kyc|pan|aadhaar|account (is )?(blocked|suspended)|refund|lottery|prize|won|winner|cashback|reward|upi|bank|credit card|loan|pay(ment)? now|electricity (bill|connection))\b""", RegexOption.IGNORE_CASE)
    private val ASKS_SECRET = Regex("""\b(share|send|tell|forward)\b.{0,30}\b(otp|pin|password|cvv|code)\b""", RegexOption.IGNORE_CASE)
    private val OTP = Regex("""\b(otp|verification code|one[- ]time password)\b.{0,40}\b\d{4,8}\b|\b\d{4,8}\b.{0,40}\b(otp|verification code)\b""", RegexOption.IGNORE_CASE)
    private val URGENT = Regex("""\b(urgent|asap|emergency|immediately|right now|hospital|accident|deadline|today|tonight|in \d+ ?(mins?|minutes|hours?))\b""", RegexOption.IGNORE_CASE)
    private val GREETING = Regex("""^\W*(good (morning|night|evening|afternoon)|gm|happy \w+day|suprabhat|shubh)\b""", RegexOption.IGNORE_CASE)

    fun signals(m: ParsedNotification): List<String> = buildList {
        val t = m.text
        if (LINK.containsMatchIn(t)) add("has_link")
        if (SHORT_LINK.containsMatchIn(t)) add("short_link")
        if (MONEY.containsMatchIn(t)) add("money_or_account_words")
        if (ASKS_SECRET.containsMatchIn(t)) add("asks_for_otp_or_pin")
        if (OTP.containsMatchIn(t)) add("otp_code")
        if (URGENT.containsMatchIn(t)) add("urgency_words")
        if (GREETING.containsMatchIn(t)) add("greeting")
        if ("@" in t) add("mentions_someone")
        if (m.senderLooksLikePhoneNumber) add("unknown_number")
        if (m.senderIsContact == false) add("not_in_contacts")
        if (t.length > 40 && t.count { it.isUpperCase() } > t.count { it.isLetter() } * 0.6) add("mostly_caps")
    }

    /** Returns a decision only when we're confident enough to skip the model. */
    fun hardRule(m: ParsedNotification, signals: List<String>): RoutingDecision? {
        val unknownSender = "unknown_number" in signals || "not_in_contacts" in signals
        if ("asks_for_otp_or_pin" in signals) {
            return RoutingDecision(Action.MUTE, MessageType.SCAM, "Asks you to share an OTP/PIN — a common scam pattern.", 0.93f, emptyList(), "rule")
        }
        if (unknownSender && "money_or_account_words" in signals && ("short_link" in signals || "has_link" in signals)) {
            return RoutingDecision(Action.MUTE, MessageType.SCAM, "Unknown sender with a link and money/account threat — likely scam.", 0.9f, emptyList(), "rule")
        }
        if ("otp_code" in signals && !unknownSender) {
            return RoutingDecision(Action.NOTIFY, MessageType.URGENT, "One-time code you probably need right now.", 0.85f, emptyList(), "rule")
        }
        return null
    }

    /** Used when the model isn't loaded or fails. Intentionally conservative: never mutes non-scams. */
    fun fallback(m: ParsedNotification, signals: List<String>, history: List<MessageEntity>): RoutingDecision {
        val corrected = history.mapNotNull { it.correction }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        Action.from(corrected)?.let {
            return RoutingDecision(it, MessageType.UNKNOWN, "You previously asked for this chat to be '${it.wire}'.", 0.6f,
                history.filter { h -> h.correction != null }.take(3).map { h -> h.id }, "fallback")
        }
        val dismissed = history.count { it.reaction == "dismissed" }
        val opened = history.count { it.reaction == "opened" || it.reaction == "read_in_app" }
        return when {
            "greeting" in signals -> RoutingDecision(Action.DIGEST, MessageType.GREETING, "Greeting message — saved for later.", 0.6f, emptyList(), "fallback")
            !m.isGroup && m.senderIsContact == true -> RoutingDecision(Action.NOTIFY, MessageType.PERSONAL, "Direct message from a saved contact.", 0.55f, emptyList(), "fallback")
            m.isGroup && "urgency_words" in signals && "mentions_someone" in signals ->
                RoutingDecision(Action.NOTIFY, MessageType.URGENT, "Group message that looks urgent and mentions someone.", 0.5f, emptyList(), "fallback")
            dismissed >= 3 && dismissed > opened * 2 ->
                RoutingDecision(Action.MUTE, MessageType.UNKNOWN, "You usually dismiss messages from this chat.", 0.55f,
                    history.filter { it.reaction == "dismissed" }.take(3).map { it.id }, "fallback")
            else -> RoutingDecision(Action.DIGEST, MessageType.UNKNOWN, "Model unavailable — holding for your digest.", 0.4f, emptyList(), "fallback")
        }
    }
}
