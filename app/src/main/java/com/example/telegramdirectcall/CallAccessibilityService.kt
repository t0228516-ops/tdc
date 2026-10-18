package com.example.telegramdirectcall

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Идея: нөмірді басқанда Telegram төменнен шағын мәзір (bottom sheet) шығарады:
 *   +7 777 123 45 67
 *   Call / Позвонить / Қоңырау шалу
 *   Add to contacts / Copy ...
 * Сервис сол мәзірді танып, нөмірді оқиды, мәзірді жабады және ACTION_CALL жасайды.
 * Сонда қолданушыға бір басу = бірден қоңырау болып көрінеді.
 */
class CallAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "DirectCallService"
        private const val DEBOUNCE_MS = 3000L

        // Мәзір кішкентай: түйін саны осыдан көп болса — бұл чат, мәзір емес
        private const val MAX_SHEET_NODES = 60

        const val PREFS = "dump_prefs"
        const val KEY_DUMPS = "dumps"

        private val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.telegram.plus"
        )

        // Мәзір пункттерінің мәтіні (en / ru / kk)
        private val CALL_LABELS = listOf("call", "позвонить", "қоңырау шалу", "қоңырау")
        private val COPY_LABELS = listOf("copy", "копировать", "көшіру", "скопировать")

        private val NUMBER_ONLY = Regex("""^\+?[\d\s\-()]{9,22}$""")
    }

    private var lastCallTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Toast.makeText(this, "Telegram Direct Call қосылды", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in TELEGRAM_PACKAGES) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() !in TELEGRAM_PACKAGES) return

        // Шағын терезе ғана өңделеді (мәзір). Чат үлкен — бірден тастаймыз.
        val texts = ArrayList<String>()
        if (!collectTexts(root, texts, MAX_SHEET_NODES)) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            saveDump(event.className?.toString(), texts)
        }

        val lower = texts.map { it.trim().lowercase() }
        val hasCall = lower.any { t -> t.length <= 25 && CALL_LABELS.any { t.startsWith(it) } }
        val hasCopy = lower.any { t -> t.length <= 25 && COPY_LABELS.any { t.startsWith(it) } }
        if (!hasCall || !hasCopy) return

        val raw = texts.firstOrNull { t ->
            val s = t.replace(' ', ' ').trim()
            NUMBER_ONLY.matches(s) && s.count { it.isDigit() } in 10..15
        } ?: return

        val number = normalize(raw)
        val now = System.currentTimeMillis()
        if (now - lastCallTime < DEBOUNCE_MS) return
        lastCallTime = now

        // Мәзірді жабамыз, содан соң қоңырау шаламыз
        performGlobalAction(GLOBAL_ACTION_BACK)
        makeCall(number)
    }

    /** Мәтіндерді жинайды. Түйін саны limit-тен асса false қайтарады. */
    private fun collectTexts(root: AccessibilityNodeInfo, out: MutableList<String>, limit: Int): Boolean {
        var count = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (++count > limit) return false
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return true
    }

    private fun normalize(raw: String): String {
        val s = raw.replace(' ', ' ').trim()
        val plus = s.startsWith("+")
        var digits = s.filter { it.isDigit() }
        if (!plus) {
            if (digits.length == 11 && digits.startsWith("8")) digits = "7" + digits.substring(1)
            if (digits.length == 10 && digits.startsWith("7")) digits = "7$digits"
        }
        return "+$digits"
    }

    private fun makeCall(number: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Қоңырау рұқсаты жоқ. Қолданбаны ашып рұқсат беріңіз.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "Қоңырау: $number", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Call failed", e)
            Toast.makeText(this, "Қоңырау басталмады", Toast.LENGTH_SHORT).show()
        }
    }

    /** Диагностика: соңғы терезелердің мәтінін сақтайды (экранда көрсету үшін). */
    private fun saveDump(className: String?, texts: List<String>) {
        if (texts.isEmpty()) return
        try {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getString(KEY_DUMPS, "") ?: ""
            val entry = "[${className?.substringAfterLast('.')}] " +
                texts.take(12).joinToString(" | ") { it.take(40) }
            val list = (listOf(entry) + old.split("\n").filter { it.isNotBlank() }).take(6)
            prefs.edit().putString(KEY_DUMPS, list.joinToString("\n")).apply()
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {}
}
