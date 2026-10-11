package com.hackday.notifrouter.ui

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hackday.notifrouter.R
import com.hackday.notifrouter.service.ChatLauncher
import com.hackday.notifrouter.data.MessageEntity
import com.hackday.notifrouter.model.Action
import com.hackday.notifrouter.model.ImageStatus

/** Icon + label + color for each routing action, used everywhere an action is shown. */
data class ActionStyle(@param:DrawableRes val icon: Int, val label: String, val container: Color, val content: Color)

@Composable
fun actionStyle(action: Action?): ActionStyle {
    val c = MaterialTheme.colorScheme
    return when (action) {
        Action.NOTIFY -> ActionStyle(R.drawable.ic_notify, "Notify", c.primaryContainer, c.onPrimaryContainer)
        Action.DIGEST -> ActionStyle(R.drawable.ic_digest, "Digest", c.tertiaryContainer, c.onTertiaryContainer)
        Action.MUTE -> ActionStyle(R.drawable.ic_mute, "Mute", c.surfaceContainerHighest, c.onSurfaceVariant)
        null -> ActionStyle(R.drawable.ic_digest, "Pending", c.secondaryContainer, c.onSecondaryContainer)
    }
}

/** Filled pill showing the action's icon and name. */
@Composable
fun ActionPill(action: Action?, modifier: Modifier = Modifier) {
    val s = actionStyle(action)
    Surface(color = s.container, contentColor = s.content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(s.icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(s.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun MessageCard(m: MessageEntity, onCorrect: (Action) -> Unit) {
    val action = Action.from(m.action)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionPill(action)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.sender, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (m.isGroup) {
                        Text(m.conversation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    DateUtils.getRelativeTimeSpanString(m.receivedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(m.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            m.reason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val image = ImageStatus.entries.firstOrNull { it.wire == m.imageStatus }?.takeIf { it != ImageStatus.NONE }
            if (image != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_image), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(image.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(
                        m.messageType,
                        m.confidence?.let { "%.0f%%".format(it * 100) },
                        m.decisionSource,
                        m.latencyMs?.let { "${it} ms" },
                        m.reaction?.let { "you ${it.replace('_', ' ')}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val ctx = LocalContext.current
                TextButton(onClick = { ChatLauncher.open(ctx, m) }) {
                    Icon(painterResource(R.drawable.ic_open_chat), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open chat")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Should be", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Action.entries.forEach { a ->
                    val s = actionStyle(a)
                    FilterChip(
                        selected = m.correction == a.wire,
                        onClick = { onCorrect(a) },
                        label = { Text(s.label) },
                        leadingIcon = { Icon(painterResource(s.icon), null, Modifier.size(FilterChipDefaults.IconSize)) },
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.padding(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
