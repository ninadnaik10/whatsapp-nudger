package com.hackday.notifrouter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hackday.notifrouter.llm.DecisionParser
import com.hackday.notifrouter.llm.GemmaEngine
import com.hackday.notifrouter.llm.PromptBuilder
import com.hackday.notifrouter.llm.RoutingContext
import com.hackday.notifrouter.model.ImageStatus
import com.hackday.notifrouter.model.MediaType
import com.hackday.notifrouter.model.ParsedNotification
import com.hackday.notifrouter.router.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Latency benchmark, debug builds only:
 *
 *   adb shell am broadcast -a com.hackday.notifrouter.BENCH -p com.hackday.notifrouter \
 *       --ez vision true --ez mtp true --ei maxTokens 64 --ei runs 6
 *
 * Image test: add `--es image /sdcard/Download/test.jpg` to send that picture as a "📷 Photo"
 * message from an unknown sender (once with the image, once without, for comparison).
 *
 * Results are logged under the "Bench" tag.
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val opts = GemmaEngine.Options(
            vision = intent.getBooleanExtra("vision", true),
            speculativeDecoding = intent.getBooleanExtra("mtp", true),
        )
        val gen = GemmaEngine.GenOptions(maxOutputTokens = intent.getIntExtra("maxTokens", 64))
        val runs = intent.getIntExtra("runs", 6)
        val imagePath = intent.getStringExtra("image")
        val app = context.applicationContext
        // The bound listener service keeps the process alive, so we don't need goAsync().
        scope.launch {
            Log.i(TAG, "BEGIN $opts $gen runs=$runs")
            if (GemmaEngine.options != opts || !GemmaEngine.isReady) {
                val t = System.currentTimeMillis()
                if (!GemmaEngine.reload(app, opts)) {
                    Log.e(TAG, "load failed: ${GemmaEngine.status.value}"); return@launch
                }
                Log.i(TAG, "load ${System.currentTimeMillis() - t}ms ${GemmaEngine.status.value.detail}")
            }
            val now = System.currentTimeMillis()
            // text, saved contact, expected action
            val samples = listOf(
                Triple("Call me when you're free, it's about tonight's dinner", true, "notify"),
                Triple("Dear customer your electricity connection will be cut tonight, call 9876543210 immediately", false, "mute"),
                Triple("Good morning all 🌞 have a blessed day", true, "digest|mute"),
                Triple("FLAT 60% OFF on all kurtas this weekend only! Shop now at our store", false, "mute|digest"),
                Triple("Dad fell in the bathroom, we're going to the hospital, come fast", true, "notify"),
                Triple("lol did you see that match yesterday", true, "digest|notify"),
            )
            val prompt = { text: String, contact: Boolean ->
                val m = ParsedNotification(
                    sbnKey = "bench", packageName = "bench", postTime = now, messageTime = now, isGroup = false,
                    conversation = "Bench", sender = "Bench", text = text, mediaType = MediaType.NONE,
                    senderIsContact = contact, senderLooksLikePhoneNumber = false, hasReplyAction = true,
                    category = null, channelId = null,
                )
                PromptBuilder.buildUserPrompt(RoutingContext(m, RuleEngine.signals(m), emptyList(), emptyList(), emptyList(), 3, false))
            }
            if (imagePath != null) {
                val bytes = try {
                    java.io.File(imagePath).readBytes()
                } catch (e: Exception) {
                    Log.e(TAG, "can't read $imagePath: $e"); return@launch
                }
                for (withImage in listOf(true, false)) {
                    val m = ParsedNotification(
                        sbnKey = "bench", packageName = "bench", postTime = now, messageTime = now, isGroup = false,
                        conversation = "+91 98056 07657", sender = "+91 98056 07657", text = "📷 Photo",
                        mediaType = MediaType.IMAGE, senderIsContact = false, senderLooksLikePhoneNumber = true,
                        hasReplyAction = true, category = null, channelId = null,
                        imageBytes = if (withImage) bytes else null,
                        imageStatus = if (withImage) ImageStatus.READ else ImageStatus.MISSING,
                    )
                    val userPrompt = PromptBuilder.buildUserPrompt(RoutingContext(m, RuleEngine.signals(m), emptyList(), emptyList(), emptyList(), 3, false))
                    val r = GemmaEngine.generate(PromptBuilder.SYSTEM_PROMPT, userPrompt, PromptBuilder.FEW_SHOT, m.imageBytes, gen)
                    Log.i(TAG, "IMAGE withImage=$withImage usedImage=${r.usedImage} $r :: ${r.text.trim()}")
                }
                Log.i(TAG, "END image test")
                return@launch
            }
            val totals = mutableListOf<Long>()
            repeat(runs) { i ->
                val (text, contact, expected) = samples[i % samples.size]
                val r = GemmaEngine.generate(PromptBuilder.SYSTEM_PROMPT, prompt(text, contact), PromptBuilder.FEW_SHOT, gen = gen)
                totals += r.totalMs
                val d = DecisionParser.parse(r.text, emptySet())
                val ok = d != null && d.action.wire in expected.split('|')
                Log.i(TAG, "run $i ${if (ok) "OK " else "BAD"} $r :: ${r.text.trim().replace('\n', ' ')}")
            }
            Log.i(TAG, "END $opts $gen median=${totals.sorted()[totals.size / 2]}ms all=$totals")
        }
    }

    companion object {
        private const val TAG = "Bench"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
