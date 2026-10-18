package com.example.telegramdirectcall

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Диалер ашылып, нөмір терілген болса — «Позвонить» түймесін басады. Басқа ештеңе істемейді.
 *
 * Қауіпсіздік: диалер жаңа ашылған сәттен кейінгі 3 секундта ғана басады
 * және бір қоңыраудан кейін 15 секунд қайта басбайды.
 */
class CallAccessibilityService : AccessibilityService() {

    companion object {
        const val PREFS = "dump_prefs"
        const val KEY_DUMP = "dump"
        private const val ARM_MS = 3000L
        private const val COOLDOWN_MS = 15000L

        private val DIALERS = setOf(
            "com.google.android.dialer", "com.android.dialer", "com.android.contacts",
            "com.samsung.android.dialer", "com.samsung.android.contacts",
            "com.oplus.dialer", "com.coloros.dialer", "com.miui.dialer"
        )
        private val CALL_WORDS = listOf("позвонить", "вызов", "набрать", "call", "dial", "қоңырау шал")
        private val SKIP_WORDS = listOf(
            "истори", "журнал", "недавн", "вызовы", "history", "recent", "calls", "log",
            "контакт", "contact"
        )
    }

    private var prevPkg: String? = null
    private var armedUntil = 0L
    private var lastClickAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg in DIALERS && prevPkg != pkg && now - lastClickAt > COOLDOWN_MS) {
                armedUntil = now + ARM_MS
            }
            if (pkg != "android" && pkg != "com.android.systemui" && !pkg.contains("inputmethod")) {
                prevPkg = pkg
            }
        }

        if (pkg in DIALERS && now < armedUntil) {
            val root = dialerRoot() ?: run { saveDump("dialer event, root=null"); return }
            if (pressCall(root)) {
                armedUntil = 0L
                lastClickAt = now
            }
        }
    }

    private fun dialerRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.toString() in DIALERS) return it }
        try {
            windows?.forEach { w ->
                val r = w.root ?: return@forEach
                if (r.packageName?.toString() in DIALERS) return r
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun pressCall(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && nodes.size < 400) {
            val n = stack.removeLast()
            nodes.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }

        // Нөмір терілген бе: әріпсіз, кемінде 3 цифрлы мәтін
        val hasNumber = nodes.any { n ->
            val t = n.text?.toString() ?: return@any false
            t.length <= 30 && t.none { it.isLetter() } && t.count { it.isDigit() } >= 3
        }
        if (!hasNumber) {
            saveDump("no number found", nodes)
            return false
        }

        // «Позвонить» түймесі: ең төменгі сәйкес элемент
        var best: AccessibilityNodeInfo? = null
        var bestTop = -1
        val r = Rect()
        for (n in nodes) {
            for (s in listOf(n.text, n.contentDescription)) {
                val label = s?.toString()?.trim()?.lowercase() ?: continue
                if (label.length > 40) continue
                if (SKIP_WORDS.any { label.contains(it) }) continue
                if (CALL_WORDS.none { label.startsWith(it) }) continue
                n.getBoundsInScreen(r)
                if (r.top > bestTop) { bestTop = r.top; best = n }
            }
        }
        val target = best
        if (target == null) {
            saveDump("call button not found", nodes)
            return false
        }
        var cur: AccessibilityNodeInfo? = target
        var depth = 0
        while (cur != null && depth++ < 5) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        saveDump("click failed", nodes)
        return false
    }

    private fun saveDump(reason: String, nodes: List<AccessibilityNodeInfo> = emptyList()) {
        val s = reason + "\n" + nodes.mapNotNull { n ->
            val t = n.text?.toString()?.take(25)
            val d = n.contentDescription?.toString()?.take(25)
            if (t == null && d == null) null
            else "t='$t' d='$d' click=${n.isClickable} id=${n.viewIdResourceName?.substringAfter(':')}"
        }.joinToString("\n")
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DUMP, s).apply()
    }

    override fun onInterrupt() {}
}
