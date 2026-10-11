package com.hackday.notifrouter.router

import android.content.Context
import android.util.Log
import com.hackday.notifrouter.AppSettings
import com.hackday.notifrouter.data.MessageDao
import com.hackday.notifrouter.data.MessageEntity
import com.hackday.notifrouter.llm.DecisionParser
import com.hackday.notifrouter.llm.GemmaEngine
import com.hackday.notifrouter.llm.PromptBuilder
import com.hackday.notifrouter.llm.RoutingContext
import com.hackday.notifrouter.model.Action
import com.hackday.notifrouter.model.ImageStatus
import com.hackday.notifrouter.model.MessageType
import com.hackday.notifrouter.model.ParsedNotification
import com.hackday.notifrouter.model.RoutingDecision
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/**
 * [fresh] is false when this message was already routed (WhatsApp re-posts unread messages).
 * [previousAction] is set when a late-arriving image caused the message to be re-decided.
 */
data class RoutedMessage(
    val id: Long,
    val message: ParsedNotification,
    val decision: RoutingDecision,
    val fresh: Boolean = true,
    val previousAction: Action? = null,
) {
    val changed: Boolean get() = previousAction != null && previousAction != decision.action
}

/** rules → history retrieval → Gemma → post-processing (quiet hours) → persist. */
class Router(
    private val context: Context,
    private val dao: MessageDao,
    private val settings: AppSettings,
) {
    /**
     * Routes a new message, or returns the stored decision for one we've already seen.
     * Returns null only if a duplicate's original decision is still in flight.
     */
    suspend fun process(m: ParsedNotification): RoutedMessage? {
        dao.findNearDuplicate(m.packageName, m.conversation, m.sender, m.text, m.messageTime, DUPLICATE_WINDOW_MS)?.let { known ->
            return if (m.imageBytes != null && known.awaitingImage()) redecideWithImage(known, m) else known.toRouted(m)
        }
        val id = dao.insert(
            MessageEntity(
                dedupKey = m.dedupKey,
                sbnKey = m.sbnKey,
                packageName = m.packageName,
                receivedAt = System.currentTimeMillis(),
                messageTime = m.messageTime,
                isGroup = m.isGroup,
                conversation = m.conversation,
                sender = m.sender,
                text = m.text,
                mediaType = m.mediaType.wire,
                senderIsContact = m.senderIsContact,
                chatId = m.chatId,
            )
        )
        if (id == -1L) return null // exact dedupKey race; the first insert is handling it

        val started = System.currentTimeMillis()
        val decision = decide(m, excludeId = id)
        dao.saveDecision(
            id = id,
            action = decision.action.wire,
            messageType = decision.messageType.wire,
            reason = decision.reason,
            confidence = decision.confidence,
            evidenceIds = decision.evidenceIds.joinToString(";").ifEmpty { "none" },
            source = decision.source,
            latencyMs = System.currentTimeMillis() - started,
            imageStatus = (if (decision.imageUsed) ImageStatus.SENT_TO_GEMMA else m.imageStatus).wire,
        )
        Log.i(TAG, "#$id ${m.conversation}/${m.sender}: ${decision.action.wire} ${decision.messageType.wire} (${decision.source}) image=${if (decision.imageUsed) "seen" else m.imageStatus.wire} ${decision.reason}")
        return RoutedMessage(id, m, decision)
    }

    /** True if this message was already routed (e.g. WhatsApp re-posting an unread message). */
    suspend fun isKnown(m: ParsedNotification): Boolean =
        dao.findNearDuplicate(m.packageName, m.conversation, m.sender, m.text, m.messageTime, DUPLICATE_WINDOW_MS) != null

    /**
     * Same pipeline as [process] (rules, real history, Gemma, quiet hours) but nothing is stored,
     * so test messages from the Try screen never show up in the feed or skew future decisions.
     */
    suspend fun preview(m: ParsedNotification): RoutingDecision = decide(m, excludeId = -1)

    private suspend fun decide(m: ParsedNotification, excludeId: Long): RoutingDecision {
        val signals = RuleEngine.signals(m)
        val history = dao.history(excludeId, m.conversation, m.sender, HISTORY_LIMIT)
        val quiet = settings.inQuietHours()

        val decision = RuleEngine.hardRule(m, signals)
            ?: llmDecision(m, signals, history, quiet)
            ?: RuleEngine.fallback(m, signals, history)

        return if (quiet && decision.action == Action.NOTIFY &&
            !(decision.messageType == MessageType.URGENT && decision.confidence >= 0.8f)
        ) {
            decision.copy(action = Action.DIGEST, reason = decision.reason + " (Held: quiet hours.)")
        } else {
            decision
        }
    }

    private suspend fun llmDecision(
        m: ParsedNotification, signals: List<String>, history: List<MessageEntity>, quiet: Boolean,
    ): RoutingDecision? {
        if (!GemmaEngine.ensureLoaded(context)) return null
        val ctx = RoutingContext(
            message = m,
            signals = signals,
            history = history,
            reactions = dao.reactionCounts(m.conversation),
            corrections = dao.correctionCounts(m.conversation),
            notifiesToday = dao.countActionSince(Action.NOTIFY.wire, startOfDay()),
            inQuietHours = quiet,
        )
        val result = try {
            withTimeoutOrNull(LLM_TIMEOUT_MS) {
                GemmaEngine.generate(PromptBuilder.SYSTEM_PROMPT, PromptBuilder.buildUserPrompt(ctx), PromptBuilder.FEW_SHOT, m.imageBytes)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LLM call failed", t)
            null
        } ?: return null
        return DecisionParser.parse(result.text, history.map { it.id }.toSet())?.copy(imageUsed = result.usedImage)
            ?: run { Log.w(TAG, "Unparseable model output: ${result.text}"); null }
    }

    /** Decided from placeholder text because the image hadn't arrived yet (rule-based decisions stand). */
    private fun MessageEntity.awaitingImage(): Boolean =
        action != null && decisionSource != "rule" &&
            (imageStatus == ImageStatus.MISSING.wire || imageStatus == ImageStatus.DENIED.wire)

    /** WhatsApp attached the photo after our media wait expired: decide again, this time looking at it. */
    private suspend fun redecideWithImage(known: MessageEntity, m: ParsedNotification): RoutedMessage? {
        val previous = Action.from(known.action) ?: return known.toRouted(m)
        val started = System.currentTimeMillis()
        val decision = decide(m, excludeId = known.id)
        dao.saveDecision(
            id = known.id,
            action = decision.action.wire,
            messageType = decision.messageType.wire,
            reason = decision.reason,
            confidence = decision.confidence,
            evidenceIds = decision.evidenceIds.joinToString(";").ifEmpty { "none" },
            source = decision.source,
            latencyMs = System.currentTimeMillis() - started,
            imageStatus = (if (decision.imageUsed) ImageStatus.SENT_TO_GEMMA else m.imageStatus).wire,
        )
        Log.i(TAG, "#${known.id} re-decided with late image: ${previous.wire} -> ${decision.action.wire} " +
            "${decision.messageType.wire} image=${if (decision.imageUsed) "seen" else m.imageStatus.wire} ${decision.reason}")
        return RoutedMessage(known.id, m, decision, fresh = false, previousAction = previous)
    }

    private fun MessageEntity.toRouted(m: ParsedNotification): RoutedMessage? {
        val action = Action.from(action) ?: return null
        val decision = RoutingDecision(
            action = action,
            messageType = MessageType.from(messageType),
            reason = reason.orEmpty(),
            confidence = confidence ?: 0f,
            evidenceIds = evidenceIds?.split(';')?.mapNotNull { it.toLongOrNull() }.orEmpty(),
            source = decisionSource ?: "stored",
        )
        return RoutedMessage(id, m, decision, fresh = false)
    }

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object {
        private const val TAG = "Router"
        private const val HISTORY_LIMIT = 6
        // WhatsApp's double-posts differ by <1 s; a wider window would swallow repeated stickers/"ok"s.
        private const val DUPLICATE_WINDOW_MS = 2_000L
        // Note: timing out doesn't cancel native inference already in progress; it only stops us waiting.
        private const val LLM_TIMEOUT_MS = 30_000L
    }
}
