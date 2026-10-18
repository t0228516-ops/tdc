package com.example.telegramdirectcall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast

/**
 * Telegram хабарламасын оқиды. Ішінде нөмір болса:
 *  - «Авто» қосулы болса — бірден қоңырау шалады,
 *  - әйтпесе «📞 Қоңырау» хабарламасын шығарады (бір басу).
 */
class TgNotificationListener : NotificationListenerService() {

    companion object {
        private const val CHANNEL_ID = "orders"
        private const val DEDUP_MS = 5 * 60 * 1000L

        private val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.telegram.plus"
        )
        private val seen = HashMap<String, Long>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in TELEGRAM_PACKAGES) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val convTitle = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val text = lastMessageText(ex) ?: return

        val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val filter = sp.getString("filter", "").orEmpty()
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (filter.isNotEmpty()) {
            val hay = "$title $convTitle".lowercase()
            if (filter.none { hay.contains(it) }) return
        }

        val number = Caller.extractNumber(text) ?: return

        // Telegram хабарламаны жаңартқанда ескі нөмірді қайта шалмау үшін
        val now = System.currentTimeMillis()
        synchronized(seen) {
            seen.entries.removeAll { now - it.value > DEDUP_MS }
            if (seen.containsKey(number)) return
            seen[number] = now
        }

        if (sp.getBoolean("auto", false)) {
            if (!Caller.call(this, number)) {
                Toast.makeText(this, "Қоңырау рұқсаты жоқ", Toast.LENGTH_LONG).show()
            }
        } else {
            showCallNotification(number, convTitle.ifEmpty { title }, text)
        }
    }

    private fun lastMessageText(ex: Bundle): String? {
        try {
            @Suppress("DEPRECATION")
            val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (msgs != null && msgs.isNotEmpty()) {
                val last = msgs.last() as? Bundle
                val t = last?.getCharSequence("text")?.toString()
                if (!t.isNullOrBlank()) return t
            }
        } catch (_: Exception) {
        }
        return (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
    }

    private fun showCallNotification(number: String, chat: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Тапсырыс нөмірлері", NotificationManager.IMPORTANCE_HIGH)
        )
        val id = (System.currentTimeMillis() % 100000).toInt()
        val intent = Intent().apply {
            component = ComponentName(this@TgNotificationListener, CallReceiver::class.java)
            putExtra("number", number)
            putExtra("notif_id", id)
        }
        val pi = PendingIntent.getBroadcast(
            this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("📞 $number")
            .setContentText("$chat: ${text.take(80)}")
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, "Қоңырау", pi).build())
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        nm.notify(id, notif)
    }
}
