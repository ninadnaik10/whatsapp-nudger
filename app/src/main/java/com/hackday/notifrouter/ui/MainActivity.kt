package com.hackday.notifrouter.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.hackday.notifrouter.R
import com.hackday.notifrouter.llm.GemmaEngine
import com.hackday.notifrouter.service.RouterListenerService

private const val REBIND_GRACE_MS = 1_500L

class MainActivity : ComponentActivity() {
    // Bumped on resume so permission/model status re-reads after returning from Settings or adb push.
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { AppScaffold(resumeTick) }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        // Give any in-flight system bind a moment, then force a rebind if we're still deaf
        // (e.g. the app was swiped away and Xiaomi's Autostart blocked the automatic rebind).
        lifecycleScope.launch {
            delay(REBIND_GRACE_MS)
            RouterListenerService.ensureBound(this@MainActivity)
        }
    }
}

private enum class Destination(val label: String, @param:DrawableRes val icon: Int) {
    HOME("Home", R.drawable.ic_home),
    TRY("Try", R.drawable.ic_science),
    SETTINGS("Settings", R.drawable.ic_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(resumeTick: Int) {
    var dest by rememberSaveable { mutableIntStateOf(Destination.HOME.ordinal) }
    val current = Destination.entries[dest]
    BackHandler(enabled = current != Destination.HOME) { dest = Destination.HOME.ordinal }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (current) {
                            Destination.HOME -> stringResource(R.string.app_name)
                            Destination.TRY -> "Try a message"
                            Destination.SETTINGS -> "Settings"
                        }
                    )
                },
                actions = { if (current == Destination.HOME) ModelStatusPill() },
            )
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = d == current,
                        onClick = { dest = d.ordinal },
                        icon = { Icon(painterResource(d.icon), contentDescription = null) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (current) {
            Destination.HOME -> HomeScreen(padding, resumeTick)
            Destination.TRY -> TryScreen(padding)
            Destination.SETTINGS -> SettingsScreen(padding, resumeTick)
        }
    }
}

/** Small "Gemma ready / loading / no model" indicator in the top bar. */
@Composable
private fun ModelStatusPill() {
    val status by GemmaEngine.status.collectAsState()
    val c = MaterialTheme.colorScheme
    val (label, dot) = when (status.state) {
        GemmaEngine.State.READY -> "Gemma ready" to c.primary
        GemmaEngine.State.LOADING -> "Loading…" to c.tertiary
        GemmaEngine.State.NO_MODEL -> "No model" to c.error
        GemmaEngine.State.ERROR -> "Model error" to c.error
        GemmaEngine.State.IDLE -> "Model idle" to c.outline
    }
    Surface(color = c.surfaceContainerHigh, shape = CircleShape, modifier = Modifier.padding(end = 12.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = dot, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
