package com.example.telegramdirectcall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

object Caller {

    // 11 цифр: +7 / 8 / 7 + 10 цифр
    private val KZ_FULL = Regex("""(?<!\d)(?:\+7|8|7)[\s\-()]*\d{3}[\s\-()]*\d{3}[\s\-]*\d{2}[\s\-]*\d{2}(?!\d)""")
    // 10 цифр, 7xx-пен басталады
    private val KZ_SHORT = Regex("""(?<!\d)7\d{2}[\s\-()]*\d{3}[\s\-]*\d{2}[\s\-]*\d{2}(?!\d)""")
    // халықаралық: +код...
    private val INTL = Regex("""(?<!\d)\+\d[\d\s\-()]{8,16}\d(?!\d)""")

    /** Мәтіннен бірінші телефон нөмірін табады (E.164 форматында) немесе null. */
    fun extractNumber(text: String): String? {
        var bestPos = Int.MAX_VALUE
        var best: String? = null
        for (re in listOf(KZ_FULL, KZ_SHORT, INTL)) {
            val m = re.find(text) ?: continue
            val num = normalize(m.value) ?: continue
            if (m.range.first < bestPos) {
                bestPos = m.range.first
                best = num
            }
        }
        return best
    }

    /** Мәтіндегі соңғы нөмір (хабарламаның соңындағы нөмірді алу үшін). */
    fun extractLastNumber(text: String): String? {
        var bestPos = -1
        var best: String? = null
        for (re in listOf(KZ_FULL, KZ_SHORT, INTL)) {
            for (m in re.findAll(text)) {
                val num = normalize(m.value) ?: continue
                if (m.range.first > bestPos) {
                    bestPos = m.range.first
                    best = num
                }
            }
        }
        return best
    }

    fun normalize(raw: String): String? {
        val s = raw.trim()
        val plus = s.startsWith("+")
        var digits = s.filter { it.isDigit() }
        if (digits.length < 3) return null
        if (plus) return if (digits.length in 10..15) "+$digits" else null
        if (digits.length == 11 && digits.startsWith("8")) digits = "7" + digits.substring(1)
        if (digits.length == 10 && digits.startsWith("7")) digits = "7$digits"
        return if (digits.length >= 10) "+$digits" else digits
    }

    /** Қоңырауды бірден шалады (TelecomManager — фоннан да істейді). */
    fun call(ctx: Context, number: String): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        val uri = Uri.fromParts("tel", number, null)
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.placeCall(uri, Bundle())
            true
        } catch (e: Exception) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
