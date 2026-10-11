package com.hackday.notifrouter.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.hackday.notifrouter.R
import com.hackday.notifrouter.service.RouterListenerService

enum class ListenerState { NOT_GRANTED, DISCONNECTED, CONNECTED }

/** Combines the permission (re-read on resume) with whether the system actually has us bound. */
@Composable
fun rememberListenerState(resumeTick: Int): ListenerState {
    val ctx = LocalContext.current
    val connected by RouterListenerService.connected.collectAsState()
    val granted = remember(resumeTick, connected) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
    return when {
        !granted -> ListenerState.NOT_GRANTED
        !connected -> ListenerState.DISCONNECTED
        else -> ListenerState.CONNECTED
    }
}

val isXiaomiFamily: Boolean
    get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

/** Xiaomi's per-app Autostart switch; without it HyperOS blocks the listener from being re-bound. */
fun openAutostartSettings(ctx: Context) {
    val autostart = Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    )
    try {
        ctx.startActivity(autostart)
    } catch (_: ActivityNotFoundException) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri()))
    } catch (_: SecurityException) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri()))
    }
}

/** Shown on Home whenever WhatsApp notifications aren't reaching the router. */
@Composable
fun ListenerWarningCard(state: ListenerState, modifier: Modifier = Modifier) {
    if (state == ListenerState.CONNECTED) return
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = c.errorContainer, contentColor = c.onErrorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_mute), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Not receiving WhatsApp notifications", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                if (state == ListenerState.NOT_GRANTED) {
                    "Notification access is off, so the router can't see incoming messages."
                } else {
                    "Android disconnected the router" + (if (isXiaomiFamily) ", usually because the app was swiped away and Autostart is off." else ".")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state == ListenerState.NOT_GRANTED) {
                    Button(
                        onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        colors = ButtonDefaults.buttonColors(containerColor = c.error, contentColor = c.onError),
                    ) { Text("Grant access") }
                } else {
                    Button(
                        onClick = { RouterListenerService.ensureBound(ctx) },
                        colors = ButtonDefaults.buttonColors(containerColor = c.error, contentColor = c.onError),
                    ) { Text("Reconnect") }
                    if (isXiaomiFamily) {
                        OutlinedButton(onClick = { openAutostartSettings(ctx) }) { Text("Allow Autostart") }
                    }
                }
            }
        }
    }
}
