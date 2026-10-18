package com.example.telegramdirectcall

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 1) Диалер ашылып, нөмір терілген болса — «Позвонить» түймесін басады.
 * 2) «Выбор SIM-карты» терезесі шықса — белсенді SIM-ді басады.
 *
 * Қауіпсіздік: диалер жаңа ашылған сәттен кейінгі 4 секундта ғана басады
 * (қолмен нөмір тергенде ештеңе басылмайды). Қоңыраулар арасында кідіріс жоқ.
 */
class CallAccessibilityService : AccessibilityService() {

    companion object {
        const val PREFS = "dump_prefs"
        const val KEY_DUMP = "dump"
        private const val ARM_MS = 4000L
        private const val SIM_MS = 10000L

        private val DIALERS = setOf(
            "com.google.android.dialer", "com.android.dialer", "com.android.contacts",
            "com.samsung.android.dialer", "com.samsung.android.contacts",
            "com.oplus.dialer", "com.coloros.dialer", "com.miui.dialer"
        )
        private val CALL_WORDS = listOf("позвонить", "вызов", "набрать", "call", "dial", "қоңырау шал")
        private val SKIP_WORDS = listOf(
            "истори", "журнал", "недавн", "вызовы", "history", "recent", "calls", "log",
            "контакт", "contact", "pad", "клавиатур", "keypad", "заверш", "end"
        )
        // Қай SIM-ді басу керек: оператор атауында осы сөз бар опция (кіші әріппен)
        private const val SIM_PREFER = "activ"
        private val SIM_TITLES = listOf(
            "выбор sim", "выбор сим", "select sim", "choose sim", "call with",
            "позвонить с помощью", "sim картасын", "sim таңдау"
        )
        private val SIM_SKIP = listOf(
            "отмена", "cancel", "запомн", "remember", "всегда", "always", "умолчан", "default",
            "настрой", "settings", "бас тарту", "есте сақта"
        )
    }

    private var prevPkg: String? = null
    private var armedUntil = 0L
    private var simUntil = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fromCall = prevPkg?.let { it.contains("incall") || it.contains("telecom") } == true
            if (pkg in DIALERS && prevPkg != pkg && !fromCall) {
                armedUntil = now + ARM_MS
                simUntil = 0L   // жаңа қоңырау — алдыңғы SIM күтуін тоқтатамыз
            }
            if (pkg != "android" && pkg != "com.android.systemui" && !pkg.contains("inputmethod")) {
                prevPkg = pkg
            }
        }

        if (now < simUntil && pkg != packageName) {
            if (pickSim()) simUntil = 0L
            return
        }

        if (pkg in DIALERS && now < armedUntil) {
            val root = dialerRoot() ?: run { saveDump("dialer event, root=null"); return }
            if (pressCall(root)) {
                armedUntil = 0L
                simUntil = now + SIM_MS   // кейін «Выбор SIM-карты» шығуы мүмкін
            }
        }
    }

    /** «Выбор SIM-карты» терезесі болса — белсенді (таңдалған/бірінші) SIM-ді басады. */
    private fun pickSim(): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try { windows?.forEach { w -> w.root?.let { roots.add(it) } } } catch (_: Exception) {}

        for (root in roots) {
            val nodes = ArrayList<AccessibilityNodeInfo>()
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            while (stack.isNotEmpty() && nodes.size < 300) {
                val n = stack.removeLast()
                nodes.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
            }
            val isSimDialog = nodes.any { n ->
                val t = n.text?.toString()?.lowercase() ?: return@any false
                SIM_TITLES.any { t.contains(it) }
            }
            if (!isSimDialog) continue

            // 1) Таңдаулы SIM («activ») — атауы бойынша
            nodes.firstOrNull { n ->
                (n.text ?: n.contentDescription)?.toString()?.lowercase()?.contains(SIM_PREFER) == true
            }?.let { pref ->
                var cur: AccessibilityNodeInfo? = pref
                var d = 0
                while (cur != null && d++ < 5) {
                    if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    cur = cur.parent
                }
            }

            // 2) Табылмаса — таңдалған/бірінші SIM
            val r = Rect()
            val options = nodes.filter { n ->
                n.isClickable && n.isEnabled && SIM_SKIP.none { labelOf(n).contains(it) }
            }.sortedBy { n -> n.getBoundsInScreen(r); r.top }
            val target = options.firstOrNull { it.isSelected || it.isChecked } ?: options.firstOrNull()
            if (target == null) {
                saveDump("sim dialog: no option", nodes)
                return false
            }
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            saveDump("sim dialog: click failed", nodes)
        }
        return false
    }

    private fun labelOf(n: AccessibilityNodeInfo, depth: Int = 0): String {
        val sb = StringBuilder()
        n.text?.let { sb.append(it).append(' ') }
        n.contentDescription?.let { sb.append(it).append(' ') }
        if (depth < 3) for (i in 0 until n.childCount) n.getChild(i)?.let { sb.append(labelOf(it, depth + 1)) }
        return sb.toString().lowercase()
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
