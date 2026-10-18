package com.example.telegramdirectcall

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.telegramdirectcall.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.btnCopyDump.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dump", b.tvDump.text))
            Toast.makeText(this, "Көшірілді", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val on = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.id.endsWith("CallAccessibilityService") }
        b.tvStatus.text = if (on) "Қызмет қосулы ✓" else "Қызмет өшірулі ✗"
        b.tvStatus.setTextColor(if (on) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        val dump = getSharedPreferences(CallAccessibilityService.PREFS, Context.MODE_PRIVATE)
            .getString(CallAccessibilityService.KEY_DUMP, "") ?: ""
        b.tvDump.text = if (dump.isBlank()) "Бос (мәселе болмаса — бос қалады)" else dump
    }
}
