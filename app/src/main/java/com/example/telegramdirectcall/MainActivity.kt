package com.example.telegramdirectcall

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.telegramdirectcall.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        updateStatus()
        if (isGranted) {
            Toast.makeText(this, "Қоңырау рұқсаты берілді", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Рұқсат берілмеді. Қоңырау шалу мүмкін емес.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.btnGrantCallPermission.setOnClickListener {
            requestCallPermission()
        }

        binding.btnCopyDump.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dump", binding.tvDump.text))
            Toast.makeText(this, "Көшірілді", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val dumps = getSharedPreferences(CallAccessibilityService.PREFS, Context.MODE_PRIVATE)
            .getString(CallAccessibilityService.KEY_DUMPS, "") ?: ""
        binding.tvDump.text = if (dumps.isBlank()) "Әзірге бос" else dumps
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val callPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        binding.tvAccessibilityStatus.text = if (accessibilityEnabled) {
            getString(R.string.status_enabled)
        } else {
            getString(R.string.status_disabled)
        }

        binding.tvCallPermissionStatus.text = if (callPermissionGranted) {
            getString(R.string.call_permission_granted)
        } else {
            getString(R.string.call_permission_denied)
        }

        binding.tvAccessibilityStatus.setTextColor(
            if (accessibilityEnabled) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
        )
        binding.tvCallPermissionStatus.setTextColor(
            if (callPermissionGranted) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val expectedId = "$packageName/.CallAccessibilityService"
        return enabledServices.any { it.id.contains(expectedId) || it.id.endsWith("CallAccessibilityService") }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "«Telegram Direct Call» қызметін тауып қосыңыз",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Параметрлерді ашу мүмкін болмады", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestCallPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                    == PackageManager.PERMISSION_GRANTED -> {
                Toast.makeText(this, "Рұқсат қазірдің өзінде бар", Toast.LENGTH_SHORT).show()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CALL_PHONE) -> {
                requestPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            }
        }
    }
}
