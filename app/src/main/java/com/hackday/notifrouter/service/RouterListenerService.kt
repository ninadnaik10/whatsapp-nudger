package com.hackday.notifrouter.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hackday.notifrouter.app
import com.hackday.notifrouter.llm.GemmaEngine
import com.hackday.notifrouter.model.Action
import com.hackday.notifrouter.model.ParsedNotification
import com.hackday.notifrouter.router.RoutedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Entry point: Android binds this service once the user grants Notification access, and keeps
 * our process alive while it's bound, which is what lets the Gemma engine stay resident.
 */
class RouterListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Phones can run one inference at a time, so notifications are processed strictly in order.
    private val queue = Channel<StatusBarNotification>(Channel.UNLIMITED)
    /** Keys we cancelled ourselves, so their removal isn't recorded as a user dismissal. */
    private val ownCancels = ConcurrentHashMap.newKeySet<String>()
    private val consumerStarted = AtomicBoolean(false)
    /** Latest version of each notification, so a held message can pick up WhatsApp's later update. */
    private val latest = ConcurrentHashMap<String, StatusBarNotification>()

    override fun onListenerConnected() {
        Log.i(TAG, "Listener connected")
        _connected.value = true
        scope.launch { GemmaEngine.ensureLoaded(applicationContext) }
        catchUp()
        // The system may reconnect us; a second consumer would break in-order processing.
        if (!consumerStarted.compareAndSet(false, true)) return
        scope.launch {
            for (sbn in queue) {
                try {
                    handle(sbn)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to route ${sbn.key}", t)
                }
            }
        }
    }

    /**
     * Messages that arrived while we were unbound (process killed, waiting for a rebind) are still
     * in the shade but we never got onNotificationPosted for them. Route them now; dedup in the
     * Router makes this safe to repeat.
     */
    private fun catchUp() {
        val active = try {
            activeNotifications
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't read active notifications", e)
            return
        } ?: return
        val missed = active.filter { it.packageName in app.settings.watchedPackages }
        if (missed.isNotEmpty()) Log.i(TAG, "Catching up on ${missed.size} notification(s) posted while disconnected")
        missed.forEach { onNotificationPosted(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val settings = app.settings
        if (!settings.enabled || sbn.packageName !in settings.watchedPackages) return
        // The system occasionally registers a listener twice (racing rebinds), delivering every
        // post twice; ignore the second delivery of the exact same posting.
        val previous = latest.put(sbn.key, sbn)
        if (previous != null && previous.postTime == sbn.postTime) return
        scope.launch(Dispatchers.IO) { app.parser.prefetchImages(sbn) }
        queue.trySend(sbn)
    }

    private suspend fun handle(sbn: StatusBarNotification) {
        val messages = app.parser.parse(sbn)
        messages.firstOrNull()?.let { ChatLauncher.remember(it.conversation, sbn.notification.contentIntent) }
        if (messages.isEmpty()) {
            // In strict mode WhatsApp's "New messages from N chats" summary would leak muted chats.
            if (app.settings.strictMode && app.parser.isMessageSummary(sbn)) cancelOwn(sbn.key)
            return
        }
        // One notification can hold several messages; the most important one decides its fate.
        val routed = awaitMedia(sbn, messages).mapNotNull { app.router.process(it) }
        val top = routed.maxByOrNull { it.decision.action.rank } ?: return
        act(sbn, top, routed)
    }

    /**
     * WhatsApp posts photo/sticker messages immediately with a "📷 Photo" placeholder and updates
     * the notification once the media has downloaded (typically ~2–3 s for photos). For new visual
     * messages without an image, poll the latest version of this notification for up to
     * [MEDIA_WAIT_MS] so Gemma can decide on the actual picture. Reading also retries, because the
     * content URI can briefly deny access right after the update.
     */
    private suspend fun awaitMedia(sbn: StatusBarNotification, messages: List<ParsedNotification>): List<ParsedNotification> {
        val result = messages.toMutableList()
        var pending = result.indices.filter { i ->
            result[i].mediaType.isVisual && result[i].imageBytes == null && !app.router.isKnown(result[i])
        }
        if (pending.isEmpty()) return result
        val started = System.currentTimeMillis()
        while (pending.isNotEmpty() && System.currentTimeMillis() - started < MEDIA_WAIT_MS) {
            delay(MEDIA_POLL_MS)
            val current = latest[sbn.key] ?: break
            val updated = app.parser.parse(current)
            for (i in pending) {
                val old = result[i]
                updated.firstOrNull {
                    it.sender == old.sender && abs(it.messageTime - old.messageTime) < 2_000 && it.imageBytes != null
                }?.let { result[i] = it }
            }
            pending = pending.filter { result[it].imageBytes == null }
        }
        val waited = System.currentTimeMillis() - started
        Log.i(TAG, "Waited ${waited}ms for media: " + if (pending.isEmpty()) "image arrived" else "deciding without image")
        return result
    }

    private suspend fun act(sbn: StatusBarNotification, top: RoutedMessage, all: List<RoutedMessage>) {
        // A late image changed a decision away from notify: withdraw the alert we posted earlier.
        all.filter { it.changed && it.previousAction == Action.NOTIFY }.forEach { app.poster.cancelImportant(it.id) }

        when (top.decision.action) {
            Action.NOTIFY -> if (app.settings.strictMode) {
                // Alert for fresh messages and for ones a late image just promoted to notify.
                // Otherwise, if our alert is still showing, silently refresh it so the photo appears.
                all.filter {
                    it.decision.action == Action.NOTIFY &&
                        (it.fresh || it.changed || (it.message.imageBytes != null && app.poster.isShowing(it.id)))
                }.forEach {
                    app.poster.postImportant(it.id, it.message, sbn.notification.contentIntent, sbn.notification.getLargeIcon())
                }
                cancelOwn(sbn.key)
            }
            Action.DIGEST -> {
                cancelOwn(sbn.key)
                if (all.any { it.fresh || it.changed }) {
                    val dao = app.db.messages()
                    app.poster.updateDigestSummary(dao.pendingDigestCount(), dao.pendingDigest(5))
                }
            }
            Action.MUTE -> cancelOwn(sbn.key)
        }
    }

    private fun cancelOwn(key: String) {
        ownCancels += key
        cancelNotification(key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        latest.remove(sbn.key)
        if (ownCancels.remove(sbn.key)) return
        val reaction = when (reason) {
            REASON_CLICK -> "opened"
            REASON_CANCEL, REASON_CANCEL_ALL -> "dismissed"
            // WhatsApp clears its own notification once the chat is read inside the app.
            REASON_APP_CANCEL -> "read_in_app"
            else -> return
        }
        val dao = app.db.messages()
        scope.launch {
            if (sbn.packageName == packageName) {
                Poster.idFromTag(sbn.tag)?.let { dao.setReaction(it, reaction) }
            } else {
                dao.setReactionBySbnKey(sbn.key, reaction)
            }
        }
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "Listener disconnected")
        _connected.value = false
        // Documented way to get the system to bind us again.
        requestRebind(ComponentName(this, RouterListenerService::class.java))
    }

    override fun onDestroy() {
        _connected.value = false
        scope.cancel()
        queue.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RouterListener"

        private val _connected = MutableStateFlow(false)
        /** True while the system has us bound. Permission alone doesn't mean notifications arrive. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /**
         * Gets the system to bind the listener again if access is granted but we aren't connected,
         * e.g. after the process was killed and Xiaomi's Autostart blocked the automatic rebind.
         *
         * `requestRebind()` only reverses our own `requestUnbind()`, so we briefly disable and
         * re-enable the service component: the package-changed event makes the notification
         * manager re-scan and bind approved listeners. Don't call this while a bind may be in
         * flight (e.g. at process start), or it can cancel that bind.
         */
        fun ensureBound(context: Context) {
            val granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            if (!granted || _connected.value) return
            Log.i(TAG, "Listener not connected; toggling component to force a rebind")
            val component = ComponentName(context, RouterListenerService::class.java)
            val pm = context.packageManager
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
        private const val MEDIA_WAIT_MS = 3_000L
        private const val MEDIA_POLL_MS = 250L
    }
}
