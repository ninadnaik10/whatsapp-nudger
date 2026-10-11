package com.hackday.notifrouter.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hackday.notifrouter.R
import com.hackday.notifrouter.data.DayStats
import com.hackday.notifrouter.model.Action

/** Rough cost of one unnecessary phone check; stated on the card so the estimate stays honest. */
private const val MINUTES_PER_AVOIDED_CHECK = 1

/**
 * Today's impact: one hero number (interruptions avoided), a notify/digest/mute split bar, and
 * stat tiles that double as the bar's legend (same order, icon + label, so identity never relies
 * on color alone).
 */
@Composable
fun ImpactCard(stats: DayStats) {
    val c = MaterialTheme.colorScheme
    // Bar fills use the strong role colors; text stays in neutral ink (never the series color).
    val segments = listOf(
        Triple(Action.NOTIFY, stats.notified, c.primary),
        Triple(Action.DIGEST, stats.digested, c.tertiary),
        Triple(Action.MUTE, stats.muted, c.outline),
    )
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_memory), null, Modifier.size(14.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    "On-device" + (stats.avgLlmLatencyMs?.let { " · %.1f s avg".format(it / 1000) } ?: ""),
                    style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant,
                )
            }

            Column {
                Text(
                    "${stats.avoided}",
                    fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface,
                )
                Text(
                    "interruptions avoided · only ${stats.notified} of ${stats.total} messages buzzed you",
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                )
            }

            SplitBar(segments)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                segments.forEach { (action, count, color) ->
                    val s = actionStyle(action)
                    StatTile(s.icon, s.label, count, color, Modifier.weight(1f))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FootStat(R.drawable.ic_shield, "${stats.blocked} scams & spam blocked")
                FootStat(R.drawable.ic_digest, "~${stats.avoided * MINUTES_PER_AVOIDED_CHECK} min saved")
            }
            Text(
                "Time saved assumes $MINUTES_PER_AVOIDED_CHECK min per phone check avoided.",
                style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
            )
        }
    }
}

/** Horizontal part-to-whole bar: 2dp surface gaps between segments, rounded outer ends. */
@Composable
private fun SplitBar(segments: List<Triple<Action, Int, Color>>) {
    val total = segments.sumOf { it.second }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val description = segments.joinToString(", ") { "${it.second} ${it.first.wire}" }
    Row(
        Modifier.fillMaxWidth().height(12.dp).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (total == 0) {
            Box(Modifier.fillMaxWidth().fillMaxHeight().background(track, RoundedCornerShape(4.dp)))
        } else {
            val shown = segments.filter { it.second > 0 } // Compose weights must be > 0
            shown.forEachIndexed { i, (_, count, color) ->
                val shape = RoundedCornerShape(
                    topStart = if (i == 0) 4.dp else 0.dp, bottomStart = if (i == 0) 4.dp else 0.dp,
                    topEnd = if (i == shown.lastIndex) 4.dp else 0.dp, bottomEnd = if (i == shown.lastIndex) 4.dp else 0.dp,
                )
                Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(color, shape))
            }
        }
    }
}

@Composable
private fun StatTile(@DrawableRes icon: Int, label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The colored icon is the legend swatch for the matching bar segment.
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FootStat(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
