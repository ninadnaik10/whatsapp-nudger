package com.hackday.notifrouter

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.hackday.notifrouter.data.AppDatabase
import com.hackday.notifrouter.parse.NotificationParser
import com.hackday.notifrouter.router.Router
import com.hackday.notifrouter.service.Poster
import java.time.LocalTime

class NotifRouterApp : Application() {
    val db by lazy { AppDatabase.create(this) }
    val settings by lazy { AppSettings(this) }
    val parser by lazy { NotificationParser(this) }
    val router by lazy { Router(this, db.messages(), settings) }
    val poster by lazy { Poster(this) }
}

val Context.app: NotifRouterApp get() = applicationContext as NotifRouterApp

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit { putBoolean("enabled", v) }

    /**
     * Strict mode: the user silences WhatsApp's own notification channels, and we re-post the
     * messages that deserve a `notify` on our high-importance channel. Without it, WhatsApp has
     * already alerted by the time we decide, so we can only clean up digest/mute afterwards.
     */
    var strictMode: Boolean
        get() = prefs.getBoolean("strict", false)
        set(v) = prefs.edit { putBoolean("strict", v) }

    var quietStart: String
        get() = prefs.getString("quietStart", "22:00")!!
        set(v) = prefs.edit { putString("quietStart", v) }

    var quietEnd: String
        get() = prefs.getString("quietEnd", "07:00")!!
        set(v) = prefs.edit { putString("quietEnd", v) }

    val watchedPackages: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun inQuietHours(now: LocalTime = LocalTime.now()): Boolean {
        val start = runCatching { LocalTime.parse(quietStart) }.getOrNull() ?: return false
        val end = runCatching { LocalTime.parse(quietEnd) }.getOrNull() ?: return false
        return if (start <= end) now >= start && now < end else now >= start || now < end
    }
}
