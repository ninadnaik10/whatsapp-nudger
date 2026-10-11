package com.hackday.notifrouter.llm

import com.hackday.notifrouter.model.Action
import com.hackday.notifrouter.model.MessageType
import com.hackday.notifrouter.model.RoutingDecision
import org.json.JSONObject

object DecisionParser {
    /**
     * Parses the one-line format `action|message_type|confidence|evidence_ids|reason`, tolerating
     * code fences or chatter around it. Falls back to JSON in case the model ignores the format.
     */
    fun parse(raw: String, allowedEvidence: Set<Long>): RoutingDecision? =
        parseLine(raw, allowedEvidence) ?: parseJson(raw, allowedEvidence)

    private fun parseLine(raw: String, allowedEvidence: Set<Long>): RoutingDecision? {
        for (line in raw.lineSequence()) {
            val parts = line.trim().trim('`').split('|', limit = 5).map { it.trim() }
            if (parts.size < 5) continue
            val action = Action.from(parts[0]) ?: continue
            val evidence = parts[3].split(',', ';', ' ').mapNotNull { it.trim().removePrefix("#").toLongOrNull() }
                .filter { it in allowedEvidence }
            return RoutingDecision(
                action = action,
                messageType = MessageType.from(parts[1]),
                reason = secondPerson(parts[4]).ifBlank { "No reason given." }.take(200),
                confidence = (parts[2].toFloatOrNull() ?: 0.5f).coerceIn(0f, 1f),
                evidenceIds = evidence,
                source = "llm",
            )
        }
        return null
    }

    /**
     * Validates enums and drops evidence ids that weren't in the prompt (hallucinated ids).
     */
    private val USER_HAS = Regex("""\b(?:the )?user has\b""", RegexOption.IGNORE_CASE)
    private val USERS = Regex("""\b(?:the )?user's\b""", RegexOption.IGNORE_CASE)
    private val USER = Regex("""\bthe user\b""", RegexOption.IGNORE_CASE)

    /** Safety net for the prompt rule: reasons speak to the reader ("you"), never about "the user". */
    internal fun secondPerson(reason: String): String {
        val r = reason.trim()
            .replace(USER_HAS, "you have")
            .replace(USERS, "your")
            .replace(USER, "you")
        return r.replaceFirstChar { it.uppercase() }
    }

    private fun parseJson(raw: String, allowedEvidence: Set<Long>): RoutingDecision? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val json = JSONObject(raw.substring(start, end + 1))
            val action = Action.from(json.optString("action")) ?: return null
            val ids = json.optJSONArray("evidence_ids")
            val evidence = (0 until (ids?.length() ?: 0)).mapNotNull { ids?.optLong(it) }.filter { it in allowedEvidence }
            RoutingDecision(
                action = action,
                messageType = MessageType.from(json.optString("message_type")),
                reason = secondPerson(json.optString("reason")).ifBlank { "No reason given." }.take(200),
                confidence = json.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f),
                evidenceIds = evidence,
                source = "llm",
            )
        } catch (e: Exception) {
            null
        }
    }
}
