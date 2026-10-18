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
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * 1) Чаттағы телефон нөмірлерінің үстіне қабат қояды: басқанда бірден қоңырау.
 * 2) Резерв: Telegram мәзірі («Позвонить по телефону») шықса — өзі басады,
 *    содан кейін диалердегі түймені басады.
 */
class CallAccessibilityService : AccessibilityService() {

    companion object {
        const val PREFS = "dump_prefs"
        const val KEY_DUMPS = "dumps"
        private const val MAX_POPUP_NODES = 50
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
            "com.oplus.dialer", "com.coloros.dialer", "com.oppo.dialer",
            "com.miui.dialer", "com.android.phone"
        )
        private val DIAL_BUTTON_LABELS = listOf(
            "позвонить", "вызов", "call", "dial", "набрать", "қоңырау", "call button"
        )
        private val CALL_LABELS = listOf(
            "позвонить по телефону", "call phone", "call by phone", "call",
            "телефон арқылы", "қоңырау шалу", "позвонить"
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
        // Бірінші рет терезені іздеу
        handler.postDelayed({ scheduleRefresh(0) }, 500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        val type = event.eventType

        // Диалер ашылса — «Позвонить» батырмасын іздейміз
        if (pkg != null && (pkg in DIALER_PACKAGES || pkg.contains("dialer") || pkg.contains("phone"))) {
            if (System.currentTimeMillis() < pendingDialUntil) tryDialer()
            return
        }

        val isTelegram = pkg != null && isTelegramPackage(pkg)
        val maybeTelegram = pkg == null && (
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )

        if (!isTelegram && !maybeTelegram) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && overlays.isNotEmpty()) {
                // Басқа қолданбаға өткен болуы мүмкін
                if (!telegramInForeground()) clearOverlays()
            }
            return
        }

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            clearOverlays()
            scheduleRefresh(200)
            return
        }

        // Мәзір (popup) болса — резервтік жол
        val roots = collectRoots()
        for (root in roots) {
            val rPkg = root.packageName?.toString()
            if (rPkg != null && !isTelegramPackage(rPkg)) continue
            if (handlePopup(root)) {
                clearOverlays()
                return
            }
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            scheduleRefresh(80)
        }
    }

    private fun isTelegramPackage(pkg: String): Boolean {
        if (pkg in TELEGRAM_PACKAGES) return true
        // кейбір fork / mod
        return pkg.startsWith("org.telegram")
    }

    private fun collectRoots(): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w ->
                w.root?.let { list.add(it) }
            }
        } catch (_: Exception) {
        }
        try {
            rootInActiveWindow?.let { active ->
                if (list.none { it == active }) list.add(active)
            }
        } catch (_: Exception) {
        }
        return list
    }

    /** Telegram терезесін агрессивтірек іздейді (OPPO/ColorOS үшін). */
    private fun findTelegramRoot(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val r = Rect()

        try {
            windows?.forEach { w ->
                val root = try {
                    w.root
                } catch (_: Exception) {
                    null
                } ?: return@forEach

                val pkg = root.packageName?.toString()
                // TYPE шектеуін алып тастадық — OPPO-да тип басқаша келуі мүмкін
                val isTg = pkg != null && isTelegramPackage(pkg)
                if (!isTg) {
                    // package null болса да ішінде telegram мәтіні бар ма — тексеру қымбат, өткіземіз
                    root.recycle()
                    return@forEach
                }

                try {
                    w.getBoundsInScreen(r)
                } catch (_: Exception) {
                    root.getBoundsInScreen(r)
                }
                val area = r.width() * r.height()
                if (area > bestArea) {
                    best?.recycle()
                    bestArea = area
                    best = root
                } else {
                    root.recycle()
                }
            }
        } catch (_: Exception) {
        }

        if (best != null) return best

        // fallback
        try {
            val active = rootInActiveWindow
            if (active != null) {
                val pkg = active.packageName?.toString()
                if (pkg != null && isTelegramPackage(pkg)) return active
                active.recycle()
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun telegramInForeground(): Boolean {
        val root = findTelegramRoot()
        root?.recycle()
        return root != null
    }

    private fun scheduleRefresh(delayMs: Long) {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, delayMs)
    }

    private fun refreshOverlays() {
        try {
            refreshOverlaysInner()
        } catch (e: Exception) {
            saveDump("ERR: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun refreshOverlaysInner() {
        val root = findTelegramRoot()
        if (root == null) {
            val info = try {
                windows?.joinToString(",") { w ->
                    val p = try {
                        w.root?.packageName?.toString()?.substringAfterLast('.') ?: "?"
                    } catch (_: Exception) {
                        "?"
                    }
                    "${w.type}/$p"
                } ?: "null"
            } catch (_: Exception) {
                "err"
            }
            saveDump("REFRESH: no telegram window; windows=$info")
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

        while (stack.isNotEmpty() && count < 1200) {
            val n = stack.removeLast()
            count++
            val raw = (n.text ?: n.contentDescription)?.toString()?.replace('\u00A0', ' ')?.trim()
            if (raw != null && raw.count { it.isDigit() } >= 9) {
                n.getBoundsInScreen(rect)
                if (samples.size < 6) {
                    samples.add(
                        "${n.className?.toString()?.substringAfterLast('.')} " +
                            "ch=${n.childCount} '${raw.take(40)}' ${rect.toShortString()}"
                    )
                }
                val visible = n.isVisibleToUser && rect.width() > 8 && rect.height() > 8
                if (visible) {
                    // 1) Толық нөмір элементі
                    if (raw.length <= 26 && NUMBER_ONLY.matches(raw) && rect.height() <= 240) {
                        Caller.extractNumber(raw)?.let {
                            found.add(Pair(Rect(rect), it))
                            exact++
                        }
                    } else {
                        // 2) Хабарлама ішіндегі нөмір — төменгі бөлігіне қабат
                        val num = Caller.extractLastNumber(raw) ?: Caller.extractNumber(raw)
                        if (num != null && rect.height() >= 40 && rect.top > 80) {
                            val r = Rect(rect)
                            // нөмір көбінесе төменде тұрады
                            if (r.height() > 80) {
                                r.top = r.top + (r.height() * 0.40).toInt()
                            }
                            if (r.left < screenW / 12) r.left = screenW / 10
                            if (r.width() > 12 && r.height() > 12) {
                                found.add(Pair(r, num))
                                msg++
                            }
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }

        saveDump("REFRESH nodes=$count exact=$exact msg=$msg; " + samples.joinToString(" || "))

        val keys = found.map { "${it.first.flattenToString()}|${it.second}" }
        if (keys == overlayKeys && overlays.isNotEmpty()) return
        clearOverlays()
        if (found.isEmpty()) return

        // Бір нөмірге бір қабат (қайталанбасын)
        val unique = LinkedHashMap<String, Rect>()
        for ((r, num) in found) {
            if (!unique.containsKey(num)) unique[num] = r
        }
        unique.entries.take(10).forEach { (num, r) -> addOverlay(r, num) }
        overlayKeys = keys
        handler.removeCallbacks(guardRunnable)
        handler.postDelayed(guardRunnable, 1000)
    }

    private fun addOverlay(rect: Rect, number: String) {
        try {
            val v = View(this)
            // Аздап көрінетін көк — қабат жұмыс істеп тұрғанын байқау үшін
            v.setBackgroundColor(0x4400AAFF)
            var downX = 0f
            var downY = 0f
            v.setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                    }
                    MotionEvent.ACTION_UP -> {
                        val moved = Math.abs(ev.rawX - downX) > 36 || Math.abs(ev.rawY - downY) > 36
                        val now = System.currentTimeMillis()
                        if (!moved && now - lastCallTime > DEBOUNCE_MS) {
                            lastCallTime = now
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (Caller.call(this, number)) {
                                Toast.makeText(this, "📞 $number", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Қоңырау рұқсаты жоқ", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                true // қабат ұстап қалады — Telegram мәзірі ашылмайды
            }
            val lp = WindowManager.LayoutParams(
                rect.width().coerceAtLeast(48),
                rect.height().coerceAtLeast(36),
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
        } catch (e: Exception) {
            saveDump("addOverlay ERR: ${e.message}")
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

        // Мәзір екенін «Копировать номер» арқылы анықтаймыз
        if (lower.none { t -> COPY_NUMBER_LABELS.any { t.startsWith(it) || t.contains(it) } }) {
            return false
        }
        saveDump("POPUP: " + texts.take(12).joinToString(" | ") { it.take(32) })

        val target = nodes.firstOrNull { n ->
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
                ?: return@firstOrNull false
            t.length <= 36 && CALL_LABELS.any { t.startsWith(it) || t == it }
        } ?: return false

        val now = System.currentTimeMillis()
        if (now - lastClickTime < DEBOUNCE_MS) return true
        lastClickTime = now

        val ok = clickNodeOrParent(target)
        if (ok) {
            pendingDialUntil = now + PENDING_MS
            // Диалерді сәл кейін қайта тексеру
            handler.postDelayed({ tryDialer() }, 400)
            handler.postDelayed({ tryDialer() }, 900)
        }
        return ok
    }

    private fun tryDialer() {
        val roots = collectRoots()
        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg in DIALER_PACKAGES || pkg.contains("dialer") || pkg.contains("phone")) {
                handleDialer(root)
                return
            }
        }
    }

    private fun handleDialer(root: AccessibilityNodeInfo) {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes, 300)
        val hasNumber = nodes.any { n ->
            val t = n.text?.toString() ?: return@any false
            t.count { it.isDigit() } >= 7 && t.length <= 28
        }
        if (!hasNumber) return

        val rect = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestTop = -1
        for (n in nodes) {
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase() ?: continue
            if (t.length > 24 || DIAL_BUTTON_LABELS.none { t.startsWith(it) || t.contains(it) }) continue
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
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        // Соңғы мүмкіндік — gesture емес, жай ғана false
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
            val list = (listOf(entry) + old.split("\n").filter { it.isNotBlank() }).take(8)
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
