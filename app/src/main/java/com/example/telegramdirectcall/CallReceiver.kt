package com.example.telegramdirectcall

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra("number") ?: return
        val id = intent.getIntExtra("notif_id", 0)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
        if (!Caller.call(context, number)) {
            Toast.makeText(context, "Қоңырау рұқсаты жоқ", Toast.LENGTH_LONG).show()
        }
    }
}
