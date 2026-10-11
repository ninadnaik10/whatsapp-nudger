package com.hackday.notifrouter

import com.hackday.notifrouter.llm.DecisionParser
import com.hackday.notifrouter.model.Action
import com.hackday.notifrouter.model.MediaType
import com.hackday.notifrouter.model.MessageType
import com.hackday.notifrouter.model.ParsedNotification
import com.hackday.notifrouter.router.RuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterLogicTest {

    private fun msg(text: String, sender: String = "Mom", contact: Boolean? = true, group: String? = null) = ParsedNotification(
        sbnKey = "k", packageName = "com.whatsapp", postTime = 0, messageTime = 0,
        isGroup = group != null, conversation = group ?: sender, sender = sender, text = text,
        mediaType = MediaType.NONE, senderIsContact = contact,
        senderLooksLikePhoneNumber = sender.startsWith("+"), hasReplyAction = true, category = null, channelId = null,
    )

    @Test fun `kyc scam from unknown number is muted by rule`() {
        val m = msg("Dear customer your account is blocked, update KYC at bit.ly/xyz", sender = "+91 98765 43210", contact = false)
        val d = RuleEngine.hardRule(m, RuleEngine.signals(m))!!
        assertEquals(Action.MUTE, d.action)
        assertEquals(MessageType.SCAM, d.messageType)
    }

    @Test fun `asking to share otp is a scam even from a contact`() {
        val m = msg("Hi beta, I sent you a code by mistake, please share the OTP")
        assertEquals(Action.MUTE, RuleEngine.hardRule(m, RuleEngine.signals(m))!!.action)
    }

    @Test fun `ordinary personal message goes to the model`() {
        val m = msg("Call me when you're free, it's about dinner tonight")
        assertNull(RuleEngine.hardRule(m, RuleEngine.signals(m)))
        assertTrue("urgency_words" in RuleEngine.signals(m))
    }

    @Test fun `fallback never mutes a contact's direct message`() {
        val m = msg("hey are you around?")
        assertEquals(Action.NOTIFY, RuleEngine.fallback(m, RuleEngine.signals(m), emptyList()).action)
    }

    @Test fun `parser validates enums and drops hallucinated evidence`() {
        val raw = """Sure! {"action":"digest","message_type":"promotion","reason":"Sale poster.","confidence":1.7,"evidence_ids":[3,99]}"""
        val d = DecisionParser.parse(raw, allowedEvidence = setOf(3L, 4L))!!
        assertEquals(Action.DIGEST, d.action)
        assertEquals(MessageType.PROMOTION, d.messageType)
        assertEquals(1f, d.confidence)
        assertEquals(listOf(3L), d.evidenceIds)
    }

    @Test fun `parser reads the one-line format inside a code fence`() {
        val d = DecisionParser.parse("```\nmute|scam|0.97|3, 99|Unknown number | asks for OTP\n```\nExtra chatter", setOf(3L))!!
        assertEquals(Action.MUTE, d.action)
        assertEquals(MessageType.SCAM, d.messageType)
        assertEquals(0.97f, d.confidence)
        assertEquals(listOf(3L), d.evidenceIds)
        assertEquals("Unknown number | asks for OTP", d.reason)
    }

    @Test fun `reasons address the reader as you`() {
        val d = DecisionParser.parse("digest|personal|0.7|none|User has mixed reactions to the user's chats", emptySet())!!
        assertEquals("You have mixed reactions to your chats", d.reason)
    }

    @Test fun `parser rejects unknown action`() {
        assertNull(DecisionParser.parse("""{"action":"maybe","message_type":"spam"}""", emptySet()))
    }
}
