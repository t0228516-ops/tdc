package com.example.telegramdirectcall

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Telegram-да нөмірді басқанда жүйелік диалер нөмірді өзі теріп ашады.
 * Бұл қызмет тек сол диалердегі «Қоңырау» түймесін басады.
 *
 * Қауіпсіздік: тек Telegram-нан диалерге өткен сәттен кейінгі 4 секундта ғана басады,
 * сондықтан қолмен нөмір теріп жатқанда ештеңе басылмайды.
 */
class CallAccessibilityService : AccessibilityService() {

    companion object {
        const val PREFS = "dump_prefs"
        const val KEY_DUMP = "dump"
        private const val ARM_MS = 4000L

        private val TELEGRAM = setOf(
            "org.telegram.messenger", "org.telegram.messenger.web",
            "org.telegram.messenger.beta", "org.telegram.plus"
        )
        private val DIALERS = setOf(
            "com.google.android.dialer", "com.android.dialer", "com.android.contacts",
            "com.samsung.android.dialer", "com.samsung.android.contacts",
            "com.oplus.dialer", "com.coloros.dialer", "com.miui.dialer", "com.android.phone"
        )
        // Түйменің мәтіні / сипаттамасы (кіші әріппен, дәл сәйкестік)
        private val CALL_LABELS = setOf(
            "call", "dial", "voice call", "позвонить", "вызов", "набрать", "звонок",
            "голосовой вызов", "аудиовызов", "қоңырау", "қоңырау шалу", "дауыстық қоңырау"
        )
        private val NUMBER = Regex("""^[+\d\s\-()]{3,25}$""")
    }

    private var prevPkg: String? = null   // соңғы алдыңғы қолданба
    private var armedUntil = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg in DIALERS && prevPkg in TELEGRAM) {
                armedUntil = System.currentTimeMillis() + ARM_MS
            }
            if (pkg != "android" && pkg != "com.android.systemui" && !pkg.contains("inputmethod")) {
                if (pkg != prevPkg) prevPkg = pkg
            }
        }

        if (pkg in DIALERS && System.currentTimeMillis() < armedUntil) {
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() in DIALERS && pressCall(root)) armedUntil = 0L
        }
    }

    /** Диалерде нөмір терілген болса, «Қоңырау» түймесін басады. */
    private fun pressCall(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && nodes.size < 300) {
            val n = stack.removeLast()
            nodes.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }

        val hasNumber = nodes.any { n ->
            val t = n.text?.toString()?.trim() ?: return@any false
            NUMBER.matches(t) && t.count { it.isDigit() } >= 3
        }
        if (!hasNumber) return false

        // Ең төменгі сәйкес түйме (үлкен жасыл түйме әдетте төменде)
        var best: AccessibilityNodeInfo? = null
        var bestTop = -1
        val r = android.graphics.Rect()
        for (n in nodes) {
            val label = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase() ?: continue
            if (label !in CALL_LABELS) continue
            n.getBoundsInScreen(r)
            if (r.top > bestTop) { bestTop = r.top; best = n }
        }
        val target = best
        if (target == null) {
            saveDump(nodes)   // түйме табылмаса — диагностика үшін сақтаймыз
            return false
        }
        var cur: AccessibilityNodeInfo? = target
        var depth = 0
        while (cur != null && depth++ < 4) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        return false
    }

    private fun saveDump(nodes: List<AccessibilityNodeInfo>) {
        val s = nodes.mapNotNull { n ->
            val t = (n.text ?: n.contentDescription)?.toString()?.take(25) ?: return@mapNotNull null
            "$t [${n.viewIdResourceName?.substringAfter(':') ?: ""}]"
        }.joinToString("\n")
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DUMP, s).apply()
    }

    override fun onInterrupt() {}
}
