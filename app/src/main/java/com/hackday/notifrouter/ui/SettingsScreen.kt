package com.hackday.notifrouter.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hackday.notifrouter.R
import com.hackday.notifrouter.app
import com.hackday.notifrouter.llm.GemmaEngine
import com.hackday.notifrouter.service.RouterListenerService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(contentPadding: PaddingValues, resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = ctx.app.settings
    val engine by GemmaEngine.status.collectAsState()
    var permTick by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    // Keyed on the ticks so status re-reads after returning from Settings or a permission dialog.
    val listener = rememberListenerState(resumeTick)
    val canPost = remember(resumeTick, permTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ctx.granted(Manifest.permission.POST_NOTIFICATIONS)
    }
    val canContacts = remember(resumeTick, permTick) { ctx.granted(Manifest.permission.READ_CONTACTS) }
    val modelFile = remember { GemmaEngine.modelFile(ctx) }
    val modelPresent = remember(resumeTick) { modelFile.exists() }

    var enabled by remember { mutableStateOf(settings.enabled) }
    var strict by remember { mutableStateOf(settings.strictMode) }
    var quietStart by remember { mutableStateOf(settings.quietStart) }
    var quietEnd by remember { mutableStateOf(settings.quietEnd) }

    Column(Modifier.padding(contentPadding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionHeader("Routing")
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_notify), null) },
            headlineContent = { Text("Routing enabled") },
            supportingContent = { Text("Let Gemma triage incoming WhatsApp notifications") },
            trailingContent = { Switch(enabled, { enabled = it; settings.enabled = it }) },
        )
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_shield), null) },
            headlineContent = { Text("Strict mode") },
            supportingContent = {
                Text(
                    if (strict) "Only messages routed to Notify alert you. Set WhatsApp's notification channels to Silent in system settings."
                    else "WhatsApp alerts as usual; digest and mute only clean up afterwards."
                )
            },
            trailingContent = { Switch(strict, { strict = it; settings.strictMode = it }) },
        )
        if (strict) {
            TextButton(
                onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, "com.whatsapp")
                    )
                },
                modifier = Modifier.padding(start = 56.dp),
            ) { Text("Open WhatsApp notification settings") }
        }
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_bedtime), null) },
            headlineContent = { Text("Quiet hours") },
            supportingContent = { Text("Notify is held for the digest unless urgent") },
        )
        Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(quietStart, { quietStart = it; settings.quietStart = it }, Modifier.weight(1f),
                label = { Text("From") }, singleLine = true)
            OutlinedTextField(quietEnd, { quietEnd = it; settings.quietEnd = it }, Modifier.weight(1f),
                label = { Text("Until") }, singleLine = true)
        }

        HorizontalDivider(Modifier.padding(top = 16.dp))
        SectionHeader("Gemma 4 model")
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_memory), null) },
            headlineContent = { Text(engine.state.name.lowercase().replaceFirstChar { it.uppercase() }) },
            supportingContent = { Text(engine.detail.ifBlank { if (modelPresent) modelFile.name else "Model file not found" }) },
            trailingContent = {
                FilledTonalButton(
                    enabled = modelPresent && engine.state != GemmaEngine.State.LOADING && engine.state != GemmaEngine.State.READY,
                    onClick = { scope.launch { GemmaEngine.ensureLoaded(ctx.applicationContext) } },
                ) { Text("Load") }
            },
        )
        if (!modelPresent) {
            Column(Modifier.padding(start = 56.dp, end = 16.dp)) {
                Text("Push the model from your computer:", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text("adb push ${GemmaEngine.MODEL_FILE_NAME} ${modelFile.parent}/",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        HorizontalDivider(Modifier.padding(top = 16.dp))
        SectionHeader("Permissions")
        ListItem(
            leadingContent = { Icon(painterResource(R.drawable.ic_notify), null) },
            headlineContent = { Text("Notification access") },
            supportingContent = {
                Text(
                    when (listener) {
                        ListenerState.CONNECTED -> "Connected · receiving WhatsApp notifications"
                        ListenerState.DISCONNECTED -> "Granted, but Android disconnected the router"
                        ListenerState.NOT_GRANTED -> "Read WhatsApp notifications"
                    }
                )
            },
            trailingContent = {
                when (listener) {
                    ListenerState.CONNECTED ->
                        Icon(painterResource(R.drawable.ic_check_circle), "Connected", tint = MaterialTheme.colorScheme.primary)
                    ListenerState.DISCONNECTED ->
                        FilledTonalButton(onClick = { RouterListenerService.ensureBound(ctx) }) { Text("Reconnect") }
                    ListenerState.NOT_GRANTED ->
                        FilledTonalButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Grant") }
                }
            },
        )
        if (isXiaomiFamily) {
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_shield), null) },
                headlineContent = { Text("Autostart (Xiaomi)") },
                supportingContent = { Text("Lets Android reconnect the router after the app is closed or swiped away") },
                trailingContent = { FilledTonalButton(onClick = { openAutostartSettings(ctx) }) { Text("Open") } },
            )
        }
        PermissionItem(R.drawable.ic_send, "Post notifications", "Re-post important messages and the digest", canPost) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermissionItem(R.drawable.ic_person, "Contacts", "Unknown senders are a strong scam signal", canContacts) {
            permLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PermissionItem(@DrawableRes icon: Int, title: String, body: String, granted: Boolean, onGrant: () -> Unit) {
    ListItem(
        leadingContent = { Icon(painterResource(icon), null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = {
            if (granted) {
                Icon(painterResource(R.drawable.ic_check_circle), "Granted", tint = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalButton(onClick = onGrant) { Text("Grant") }
            }
        },
    )
}

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
