package com.example.telegramdirectcall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * ACTION_DIAL tel:... ұстайды (Telegram «Позвонить по телефону» осыны жібереді)
 * және диалерді ашудың орнына бірден ACTION_CALL жасайды.
 */
class DialInterceptActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val raw = intent?.data?.schemeSpecificPart.orEmpty()
            val number = normalize(raw)
            if (number == null) {
                Toast.makeText(this, "Нөмір табылмады", Toast.LENGTH_SHORT).show()
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Қоңырау рұқсаты жоқ. Қолданбаны ашып рұқсат беріңіз.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Қоңырау басталмады", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val plus = s.startsWith("+")
        var digits = s.filter { it.isDigit() }
        if (digits.length < 3) return null
        if (!plus) {
            if (digits.length == 11 && digits.startsWith("8")) digits = "7" + digits.substring(1)
            if (digits.length == 10 && digits.startsWith("7")) digits = "7$digits"
            // қысқа/қызметтік нөмірлер (112 т.б.) өзгеріссіз
            return if (digits.length >= 10) "+$digits" else digits
        }
        return "+$digits"
    }
}
