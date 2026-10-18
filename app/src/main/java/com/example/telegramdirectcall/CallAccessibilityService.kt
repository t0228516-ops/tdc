package com.example.telegramdirectcall

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Нөмірді басқанда Telegram шағын мәзір (popup) көрсетеді:
 *   Добавить в контакты / Пригласить в Telegram / Позвонить по телефону / Копировать номер
 * Бұл сервис сол мәзірді танып, «Позвонить по телефону» пунктін өзі басады.
 * Одан кейін Telegram ACTION_DIAL жібереді, оны DialInterceptActivity ұстап, бірден қоңырау шалады.
 */
class CallAccessibilityService : AccessibilityService() {

    companion object {
        const val PREFS = "dump_prefs"
        const val KEY_DUMPS = "dumps"
        private const val MAX_POPUP_NODES = 40
        private const val DEBOUNCE_MS = 1200L

        private val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.telegram.plus"
        )

        private val DIALER_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.contacts",
            "com.samsung.android.dialer",
            "com.samsung.android.contacts",
            "com.oplus.dialer",
            "com.coloros.dialer",
            "com.miui.dialer"
        )
        private const val PENDING_MS = 8000L

        // Диалердегі «Позвонить» түймесі
        private val DIAL_BUTTON_LABELS = listOf("позвонить", "вызов", "call", "dial", "набрать", "қоңырау")

        // «Позвонить по телефону» пункті (ru / en / kk)
        private val CALL_LABELS = listOf(
            "позвонить по телефону", "call phone", "call by phone", "call",
            "телефон арқылы", "қоңырау шалу"
        )
        // «Копировать номер» пункті — popup екенін растайды
        private val COPY_NUMBER_LABELS = listOf(
            "копировать номер", "copy number", "copy phone", "нөмірді көшіру"
        )
    }

    private var lastClickTime = 0L
    private var pendingDialUntil = 0L
    private var lastDump = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Toast.makeText(this, "Telegram Direct Call қосылды", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()

        // Диалер ашылса және соңғы 8 с ішінде Telegram-да «Позвонить» басылса — диалердегі түймені басамыз
        if (pkg != null && pkg in DIALER_PACKAGES) {
            if (System.currentTimeMillis() < pendingDialUntil) {
                tryDialer()
            }
            return
        }
        if (pkg != null && pkg !in TELEGRAM_PACKAGES) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> return
        }

        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w -> w.root?.let { roots.add(it) } }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let { roots.add(it) }

        for (root in roots) {
            if (root.packageName?.toString() !in TELEGRAM_PACKAGES) continue
            if (handlePopup(root)) return
        }
    }

    /** true — popup табылып, басу жасалды */
    private fun handlePopup(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        if (!collect(root, nodes, MAX_POPUP_NODES)) return false // үлкен терезе = чат

        val texts = nodes.mapNotNull { n ->
            (n.text ?: n.contentDescription)?.toString()?.takeIf { it.isNotBlank() }
        }
        saveDump(texts)

        val lower = texts.map { it.trim().lowercase() }
        if (lower.none { t -> COPY_NUMBER_LABELS.any { t.startsWith(it) } }) return false

        val target = nodes.firstOrNull { n ->
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: return@firstOrNull false
            t.length <= 30 && CALL_LABELS.any { t.startsWith(it) }
        } ?: return false

        val now = System.currentTimeMillis()
        if (now - lastClickTime < DEBOUNCE_MS) return true
        lastClickTime = now

        val ok = clickNodeOrParent(target)
        if (ok) pendingDialUntil = now + PENDING_MS
        return ok
    }

    private fun tryDialer() {
        val root = rootInActiveWindow
        if (root != null && root.packageName?.toString() in DIALER_PACKAGES) {
            handleDialer(root)
            return
        }
        try {
            windows?.forEach { w ->
                val r = w.root ?: return@forEach
                if (r.packageName?.toString() in DIALER_PACKAGES) {
                    handleDialer(r)
                    return
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Диалердің нөмір терілген экранында төменгі «Позвонить» түймесін басады. */
    private fun handleDialer(root: AccessibilityNodeInfo) {
        if (root.packageName?.toString() !in DIALER_PACKAGES) return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes, 250)

        // экранда терілген нөмір бар болуы керек (кемінде 7 цифр)
        val hasNumber = nodes.any { n ->
            val t = n.text?.toString() ?: return@any false
            t.count { it.isDigit() } >= 7 && t.length <= 25
        }
        if (!hasNumber) return

        // «Позвонить» түймелерінің ішінен ең төменгісін аламыз (терілген нөмірдің үлкен түймесі)
        val rect = android.graphics.Rect()
        var best: AccessibilityNodeInfo? = null
        var bestTop = -1
        for (n in nodes) {
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: continue
            if (t.length > 20 || DIAL_BUTTON_LABELS.none { t.startsWith(it) }) continue
            n.getBoundsInScreen(rect)
            if (rect.top > bestTop) {
                bestTop = rect.top
                best = n
            }
        }
        val target = best ?: return
        if (clickNodeOrParent(target)) pendingDialUntil = 0L
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun collect(root: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, limit: Int): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (out.size >= limit) return false
            out.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return true
    }

    /** Диагностика: шағын терезелердің мәтінін сақтайды. */
    private fun saveDump(texts: List<String>) {
        if (texts.isEmpty()) return
        val entry = texts.take(12).joinToString(" | ") { it.take(40) }
        if (entry == lastDump) return
        lastDump = entry
        try {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getString(KEY_DUMPS, "") ?: ""
            val list = (listOf(entry) + old.split("\n").filter { it.isNotBlank() }).take(6)
            prefs.edit().putString(KEY_DUMPS, list.joinToString("\n")).apply()
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {}
}
