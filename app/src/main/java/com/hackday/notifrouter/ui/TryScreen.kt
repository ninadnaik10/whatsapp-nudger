package com.hackday.notifrouter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.hackday.notifrouter.R
import com.hackday.notifrouter.app
import com.hackday.notifrouter.model.MediaType
import com.hackday.notifrouter.model.ParsedNotification
import com.hackday.notifrouter.model.RoutingDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SAMPLES = listOf(
    Triple("Mom", "Call me when you're free, it's about tonight's dinner", true),
    Triple("+91 70421 88310", "Your account will be blocked today. Update KYC: bit.ly/kyc-upd", false),
    Triple("Uncle Ravi", "Good morning 🌸 have a blessed day", true),
    Triple("Priya", "Dad fell in the bathroom, we're going to the hospital, come fast", true),
)

/** Runs the full router on a typed message — handy for demos without waiting for real WhatsApp traffic. */
@Composable
fun TryScreen(contentPadding: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sender by rememberSaveable { mutableStateOf(SAMPLES[0].first) }
    var group by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf(SAMPLES[0].second) }
    var isContact by rememberSaveable { mutableStateOf(SAMPLES[0].third) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RoutingDecision?>(null) }
    var elapsedMs by remember { mutableStateOf(0L) }

    Column(
        Modifier.padding(contentPadding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Route a test message through the same rules + Gemma pipeline that handles WhatsApp. Results are only shown here and aren't saved.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SAMPLES.forEachIndexed { i, (s, t, c) ->
                SuggestionChip(onClick = { sender = s; text = t; isContact = c; group = "" }, label = { Text("Sample ${i + 1}") })
            }
        }
        OutlinedTextField(sender, { sender = it }, Modifier.fillMaxWidth(), label = { Text("Sender") }, singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_person), null) })
        OutlinedTextField(group, { group = it }, Modifier.fillMaxWidth(), label = { Text("Group (optional)") }, singleLine = true)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Message") }, minLines = 3)
        ListItem(
            headlineContent = { Text("Saved contact") },
            supportingContent = { Text("Unknown senders are treated with more suspicion") },
            trailingContent = { Switch(isContact, { isContact = it }) },
        )
        Button(
            enabled = !running && text.isNotBlank() && sender.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                running = true
                result = null
                scope.launch {
                    val now = System.currentTimeMillis()
                    val msg = ParsedNotification(
                        sbnKey = "sim:$now", packageName = "simulator", postTime = now, messageTime = now,
                        isGroup = group.isNotBlank(), conversation = group.ifBlank { sender }, sender = sender, text = text,
                        mediaType = MediaType.NONE, senderIsContact = isContact,
                        senderLooksLikePhoneNumber = Regex("""^\+?[\d\s\-()]{7,}$""").matches(sender.trim()),
                        hasReplyAction = true, category = "msg", channelId = null,
                    )
                    val started = System.currentTimeMillis()
                    result = withContext(Dispatchers.Default) { ctx.app.router.preview(msg) }
                    elapsedMs = System.currentTimeMillis() - started
                    running = false
                }
            },
        ) {
            if (running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Routing…")
            } else {
                Icon(painterResource(R.drawable.ic_send), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Route message")
            }
        }

        result?.let { d ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionPill(d.action)
                        Spacer(Modifier.width(12.dp))
                        Text(d.messageType.wire, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text("%.0f%%".format(d.confidence * 100), style = MaterialTheme.typography.labelLarge)
                    }
                    Text(d.reason, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                    Text(
                        "Decided by ${d.source} in $elapsedMs ms · preview only, not saved to your feed",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
