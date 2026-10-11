package com.hackday.notifrouter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hackday.notifrouter.R
import com.hackday.notifrouter.app
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(contentPadding: PaddingValues, resumeTick: Int) {
    val ctx = LocalContext.current
    val dao = remember { ctx.app.db.messages() }
    val feed by remember { dao.feed() }.collectAsState(initial = emptyList())
    val digest by remember { dao.digest() }.collectAsState(initial = emptyList())
    // Keyed on the date so the card rolls over at midnight.
    val today = LocalDate.now()
    val stats by remember(today) {
        dao.statsSince(today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }.collectAsState(initial = null)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val listener = rememberListenerState(resumeTick)

    Column(Modifier.padding(contentPadding).fillMaxSize()) {
        ListenerWarningCard(listener, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0, onClick = { tab = 0 },
                text = { Text("Feed") },
                icon = { Icon(painterResource(R.drawable.ic_feed), null) },
            )
            Tab(
                selected = tab == 1, onClick = { tab = 1 },
                text = { Text("Digest") },
                icon = {
                    BadgedBox(badge = { if (digest.isNotEmpty()) Badge { Text("${digest.size}") } }) {
                        Icon(painterResource(R.drawable.ic_digest), null)
                    }
                },
            )
        }
        Box(Modifier.fillMaxSize()) {
            val rows = if (tab == 0) feed else digest
            if (rows.isEmpty()) {
                if (tab == 0) {
                    EmptyState(R.drawable.ic_feed, "No messages yet", "WhatsApp messages and their routing decisions will appear here.")
                } else {
                    EmptyState(R.drawable.ic_digest, "Digest is empty", "Messages that can wait are collected here.")
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (tab == 0) stats?.takeIf { it.total > 0 }?.let { s -> item(key = "impact") { ImpactCard(s) } }
                    items(rows, key = { it.id }) { m ->
                        MessageCard(m) { a -> scope.launch { dao.setCorrection(m.id, a.wire) } }
                    }
                }
            }
            if (tab == 1 && digest.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            dao.clearDigest()
                            ctx.app.poster.updateDigestSummary(0, emptyList())
                        }
                    },
                    icon = { Icon(painterResource(R.drawable.ic_clear_all), null) },
                    text = { Text("Clear digest") },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
    }
}
