package com.example.telegramdirectcall

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 1) Чаттағы телефон нөмірлерінің үстіне көрінбейтін қабат қояды: басқанда бірден қоңырау шалынады.
 * 2) Резерв: Telegram мәзірі («Позвонить по телефону») шықса, оны өзі басады,
 *    содан кейін диалердегі «Позвонить» түймесін басады.
 */
class CallAccessibilityService : AccessibilityService() {

    companion object {
        const val PREFS = "dump_prefs"
        const val KEY_DUMPS = "dumps"
        private const val MAX_POPUP_NODES = 40
        private const val DEBOUNCE_MS = 1200L
        private const val PENDING_MS = 8000L

        private val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.telegram.plus"
        )
        private val DIALER_PACKAGES = setOf(
            "com.google.android.dialer", "com.android.dialer", "com.android.contacts",
            "com.samsung.android.dialer", "com.samsung.android.contacts",
            "com.oplus.dialer", "com.coloros.dialer", "com.miui.dialer"
        )
        private val DIAL_BUTTON_LABELS = listOf("позвонить", "вызов", "call", "dial", "набрать", "қоңырау")
        private val CALL_LABELS = listOf(
            "позвонить по телефону", "call phone", "call by phone", "call",
            "телефон арқылы", "қоңырау шалу"
        )
        private val COPY_NUMBER_LABELS = listOf(
            "копировать номер", "copy number", "copy phone", "нөмірді көшіру"
        )
        private val NUMBER_ONLY = Regex("""^\+?[\d\s\-()]{9,22}$""")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val overlays = ArrayList<View>()
    private var overlayKeys: List<String> = emptyList()

    private var lastClickTime = 0L
    private var lastCallTime = 0L
    private var pendingDialUntil = 0L
    private var lastDump = ""

    private val refreshRunnable = Runnable { refreshOverlays() }
    private val guardRunnable = object : Runnable {
        override fun run() {
            if (overlays.isNotEmpty()) {
                if (!telegramInForeground()) clearOverlays()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Toast.makeText(this, "Telegram Direct Call қосылды", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        val type = event.eventType

        if (pkg != null && pkg in DIALER_PACKAGES) {
            if (System.currentTimeMillis() < pendingDialUntil) tryDialer()
            return
        }

        val isTelegram = (pkg != null && pkg in TELEGRAM_PACKAGES) ||
            (pkg == null && type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        if (!isTelegram) {
            // басқа қолданбаға өтсек — қабаттарды алып тастаймыз
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && overlays.isNotEmpty()) clearOverlays()
            return
        }

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            clearOverlays()
            scheduleRefresh(250)
            return
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        // Мәзір (popup) болса — резервтік жол
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w -> w.root?.let { roots.add(it) } }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let { roots.add(it) }
        for (root in roots) {
            if (root.packageName?.toString() !in TELEGRAM_PACKAGES) continue
            if (handlePopup(root)) {
                clearOverlays()
                return
            }
        }

        scheduleRefresh(120)
    }

    // ───────────── Қабаттар (overlay) ─────────────

    private fun scheduleRefresh(delayMs: Long) {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, delayMs)
    }

    private fun telegramInForeground(): Boolean {
        val r = rootInActiveWindow ?: return false
        return r.packageName?.toString() in TELEGRAM_PACKAGES
    }

    private fun refreshOverlays() {
        try {
            refreshOverlaysInner()
        } catch (e: Exception) {
            saveDump("ERR: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun refreshOverlaysInner() {
        val root = rootInActiveWindow
        if (root == null) {
            saveDump("REFRESH: root=null")
            clearOverlays()
            return
        }
        if (root.packageName?.toString() !in TELEGRAM_PACKAGES) {
            clearOverlays()
            return
        }
        val screenW = resources.displayMetrics.widthPixels
        val found = ArrayList<Pair<Rect, String>>()
        val samples = ArrayList<String>()
        var count = 0
        var exact = 0
        var msg = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val rect = Rect()
        while (stack.isNotEmpty() && count < 900) {
            val n = stack.removeLast()
            count++
            val t = (n.text ?: n.contentDescription)?.toString()?.replace('\u00A0', ' ')?.trim()
            if (t != null && t.count { it.isDigit() } >= 9) {
                n.getBoundsInScreen(rect)
                if (samples.size < 5) {
                    samples.add("${n.className?.toString()?.substringAfterLast('.')} ch=${n.childCount} act=${n.actionList.size} '${t.take(45)}' $rect")
                }
                val visible = n.isVisibleToUser && rect.width() > 10 && rect.height() > 10
                if (visible) {
                    if (t.length <= 24 && NUMBER_ONLY.matches(t) && rect.height() <= 220) {
                        // толық нөмір элементі — дәл орны
                        Caller.extractNumber(t)?.let { found.add(Pair(Rect(rect), it)); exact++ }
                    } else if (rect.height() >= 60 && rect.top > 150) {
                        // нөмірі бар хабарлама — төменгі бөлігіне қабат қоямыз
                        Caller.extractLastNumber(t)?.let { num ->
                            val r = Rect(rect)
                            r.top = r.top + (r.height() * 0.45).toInt()
                            if (r.left < screenW / 10) r.left = screenW / 8
                            if (r.width() > 10 && r.height() > 10) {
                                found.add(Pair(r, num)); msg++
                            }
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }

        saveDump("REFRESH nodes=$count exact=$exact msg=$msg; " + samples.joinToString(" || "))

        val keys = found.map { "${it.first.flattenToString()}|${it.second}" }
        if (keys == overlayKeys && overlays.isNotEmpty()) return
        clearOverlays()
        if (found.isEmpty()) return
        found.take(12).forEach { addOverlay(it.first, it.second) }
        overlayKeys = keys
        handler.removeCallbacks(guardRunnable)
        handler.postDelayed(guardRunnable, 1000)
    }

    private fun addOverlay(rect: Rect, number: String) {
        try {
            val v = View(this)
            v.setBackgroundColor(0x330088CC) // өте ақшыл көк: белсенді екенін көрсетеді
            var downX = 0f
            var downY = 0f
            v.setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY
                    }
                    MotionEvent.ACTION_UP -> {
                        val moved = Math.abs(ev.rawX - downX) > 40 || Math.abs(ev.rawY - downY) > 40
                        val now = System.currentTimeMillis()
                        if (!moved && now - lastCallTime > DEBOUNCE_MS) {
                            lastCallTime = now
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (!Caller.call(this, number)) {
                                Toast.makeText(this, "Қоңырау рұқсаты жоқ", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                true
            }
            val lp = WindowManager.LayoutParams(
                rect.width(), rect.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = rect.left
            lp.y = rect.top
            wm.addView(v, lp)
            overlays.add(v)
        } catch (_: Exception) {
        }
    }

    private fun clearOverlays() {
        handler.removeCallbacks(guardRunnable)
        for (v in overlays) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
        overlays.clear()
        overlayKeys = emptyList()
    }

    // ───────────── Резерв: мәзірді басу ─────────────

    private fun handlePopup(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        if (!collect(root, nodes, MAX_POPUP_NODES)) return false

        val texts = nodes.mapNotNull { n ->
            (n.text ?: n.contentDescription)?.toString()?.takeIf { it.isNotBlank() }
        }
        val lower = texts.map { it.trim().lowercase() }
        if (lower.none { t -> COPY_NUMBER_LABELS.any { t.startsWith(it) } }) return false
        saveDump("POPUP: " + texts.take(10).joinToString(" | ") { it.take(30) })

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

    private fun handleDialer(root: AccessibilityNodeInfo) {
        if (root.packageName?.toString() !in DIALER_PACKAGES) return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes, 250)
        val hasNumber = nodes.any { n ->
            val t = n.text?.toString() ?: return@any false
            t.count { it.isDigit() } >= 7 && t.length <= 25
        }
        if (!hasNumber) return

        val rect = Rect()
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

    private fun saveDump(entry: String) {
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

    override fun onInterrupt() {
        clearOverlays()
    }

    override fun onDestroy() {
        clearOverlays()
        super.onDestroy()
    }
}
