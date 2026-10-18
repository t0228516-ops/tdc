package com.example.telegramdirectcall

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

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
        private const val SIM_PREFER = "activ"
        private const val SIM_MS = 10000L
        private val SIM_TITLES = listOf(
            "выбор sim", "выбор сим", "select sim", "choose sim", "call with",
            "позвонить с помощью", "sim картасын", "sim таңдау"
        )
        private val SIM_SKIP = listOf(
            "отмена", "cancel", "запомн", "remember", "всегда", "always", "умолчан", "default",
            "настрой", "settings", "бас тарту", "есте сақта"
        )
        private val NUMBER_ONLY = Regex("""^\+?[\d\s\-()]{9,22}$""")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val overlays = ArrayList<View>()
    private var overlayKeys: List<String> = emptyList()

    private var simUntil = 0L
    private var lastClickTime = 0L
    private var lastCallTime = 0L
    private var pendingDialUntil = 0L
    private var lastDump = ""
    private var lastEventSource: AccessibilityNodeInfo? = null

    private var fgPkg: String? = null
    private var ocrBusy = false
    private var lastCaptureAt = 0L
    private var scrollGen = 0
    private var a11yNums: Set<String> = emptySet()
    private var guardRunning = false
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val refreshRunnable = Runnable { refreshOverlays() }
    private val guardRunnable = object : Runnable {
        override fun run() {
            if (!isTelegramFg()) {
                guardRunning = false
                clearOverlays()
                return
            }
            scheduleRefresh(0)
            handler.postDelayed(this, 2500)
        }
    }

    /** Telegram экранда ма: терезе көрінсе — иә, әйтпесе соңғы оқиғаға қарай. */
    private fun isTelegramFg(): Boolean {
        if (findTelegramRoot() != null) return true
        return fgPkg != null && fgPkg in TELEGRAM_PACKAGES
    }

    private fun ensureGuard() {
        if (!guardRunning) {
            guardRunning = true
            handler.postDelayed(guardRunnable, 2500)
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

        if (System.currentTimeMillis() < simUntil && pkg != packageName) {
            if (pickSim()) simUntil = 0L
        }

        if ((type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
                type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) && isTelegramFg()
        ) {
            saveDump("EV ${evName(type)} pkg=$pkg")
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null &&
            pkg != "com.android.systemui" && pkg != packageName && pkg != "android" &&
            !pkg.contains("inputmethod") && !pkg.contains("keyboard")
        ) {
            fgPkg = pkg
        }

        if (pkg != null && pkg in DIALER_PACKAGES) {
            if (System.currentTimeMillis() < pendingDialUntil) tryDialer()
            return
        }

        val isTelegram = (pkg != null && pkg in TELEGRAM_PACKAGES) ||
            (pkg == null && type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        if (!isTelegram) {
            // басқа ШЫН қолданбаға өтсек — қабаттарды алып тастаймыз
            // (өз қабатымыздың, systemui мен пернетақтаның оқиғалары есепке алынбайды)
            val realOtherApp = pkg != null && pkg != packageName && pkg != "com.android.systemui" &&
                pkg != "android" && !pkg.contains("inputmethod") && !pkg.contains("keyboard")
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && realOtherApp &&
                overlays.isNotEmpty() && !isTelegramFg()
            ) {
                clearOverlays()
            }
            return
        }

        try {
            var n = event.source
            if (n != null) {
                var guard = 0
                while (n!!.parent != null && guard++ < 40) n = n.parent
                lastEventSource = n
            }
        } catch (_: Exception) {
        }

        fgPkg = pkg ?: fgPkg
        ensureGuard()

        logEvent(event)
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && handleTap(event)) return

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scrollGen++
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

    private fun evName(t: Int): String? = when (t) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "FOCUS"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "A11YFOCUS"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "SELECTED"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "TOUCH_START"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "TOUCH_END"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WSTATE"
        else -> null
    }

    private fun eventText(event: AccessibilityEvent): String {
        val sb = StringBuilder()
        event.text?.forEach { sb.append(it).append(' ') }
        try {
            event.source?.let { src ->
                src.text?.let { sb.append(it).append(' ') }
                src.contentDescription?.let { sb.append(it) }
            }
        } catch (_: Exception) {
        }
        return sb.toString()
    }

    private fun logEvent(event: AccessibilityEvent) {
        val name = evName(event.eventType) ?: return
        val txt = eventText(event).replace('\n', ' ').take(60)
        saveDump("EV $name cls=${event.className?.toString()?.substringAfterLast('.')} '$txt'")
    }

    /** Басу оқиғасы келсе және хабарламада бір ғана нөмір болса — бірден қоңырау. */
    private fun handleTap(event: AccessibilityEvent): Boolean {
        val nums = Caller.findNumbers(eventText(event)).map { it.second }.distinct()
        if (nums.size != 1) return false
        val now = System.currentTimeMillis()
        if (now - lastCallTime < DEBOUNCE_MS) return true
        lastCallTime = now
        Toast.makeText(this, "📞 ${nums[0]}", Toast.LENGTH_SHORT).show()
        simUntil = now + SIM_MS
        Caller.call(this, nums[0])
        return true
    }

    private fun scheduleRefresh(delayMs: Long) {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, delayMs)
    }

    /** Telegram терезесінің түбірін windows тізімінен іздейді (rootInActiveWindow null болуы мүмкін). */
    private fun findTelegramRoot(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        try {
            val r = Rect()
            windows?.forEach { w ->
                if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
                val root = w.root ?: return@forEach
                if (root.packageName?.toString() !in TELEGRAM_PACKAGES) return@forEach
                w.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) {
                    bestArea = area
                    best = root
                }
            }
        } catch (_: Exception) {
        }
        if (best != null) return best
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() in TELEGRAM_PACKAGES) return active
        // соңғы жол: оқиғаның көзінен өрлеп шыққан түбір
        val src = lastEventSource
        if (src != null) {
            try {
                if (src.refresh() && src.packageName?.toString() in TELEGRAM_PACKAGES) return src
            } catch (_: Exception) {
            }
        }
        return null
    }

    private val overlaysEnabled = true

    private fun refreshOverlays() {
        if (!overlaysEnabled) return
        try {
            refreshOverlaysInner()
        } catch (e: Exception) {
            saveDump("ERR: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun refreshOverlaysInner() {
        val root = findTelegramRoot()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Дәл орны — экран суреті + OCR; дәл цифрлары — Accessibility мәтіні (бар болса)
            if (!isTelegramFg()) {
                clearOverlays()
                return
            }
            a11yNums = if (root != null) collectA11yNumbers(root) else emptySet()
            captureAndOcr()
            return
        }
        if (root == null) {
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

        applyOverlays(found)
    }

    private fun collectA11yNumbers(root: AccessibilityNodeInfo): Set<String> {
        val out = HashSet<String>()
        var count = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && count < 900) {
            val n = stack.removeLast()
            count++
            val t = (n.text ?: n.contentDescription)?.toString()
            if (t != null && t.count { it.isDigit() } >= 9) {
                Caller.findNumbers(t).forEach { out.add(it.second) }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return out
    }

    /** OCR нөмірін Accessibility-дегі дәл нөмірмен тексереді/түзетеді (≤2 цифр айырмашылық). */
    private fun verifyNumber(ocr: String): String? {
        if (a11yNums.isEmpty()) return ocr
        if (ocr in a11yNums) return ocr
        var best: String? = null
        var bestD = 3
        for (c in a11yNums) {
            if (c.length != ocr.length) continue
            var d = 0
            for (i in c.indices) if (c[i] != ocr[i]) d++
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return best
    }

    private fun applyOverlays(found: List<Pair<Rect, String>>) {
        val keys = found.map {
            val r = it.first
            "${r.left / 12},${r.top / 12},${r.right / 12},${r.bottom / 12}|${it.second}"
        }
        if (keys == overlayKeys && overlays.isNotEmpty()) return
        clearOverlays()
        if (found.isEmpty()) return
        found.take(12).forEach { addOverlay(it.first, it.second) }
        overlayKeys = keys
        saveDump("OVERLAYS added=${overlays.size} of ${found.size}")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun captureAndOcr() {
        if (ocrBusy) return
        val now = SystemClock.elapsedRealtime()
        val wait = 800 - (now - lastCaptureAt)
        if (wait > 0) {
            scheduleRefresh(wait)
            return
        }
        ocrBusy = true
        lastCaptureAt = now
        val gen = scrollGen
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(this),
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        result.hardwareBuffer.close()
                        if (bmp == null) {
                            ocrBusy = false
                            return
                        }
                        recognizer.process(InputImage.fromBitmap(bmp, 0))
                            .addOnSuccessListener { t ->
                                ocrBusy = false
                                bmp.recycle()
                                onOcr(t, gen)
                            }
                            .addOnFailureListener { e ->
                                ocrBusy = false
                                bmp.recycle()
                                saveDump("OCR fail: ${e.message}")
                            }
                    } catch (e: Exception) {
                        ocrBusy = false
                        saveDump("OCR err: ${e.javaClass.simpleName} ${e.message}")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    ocrBusy = false
                    saveDump("SCREENSHOT fail code=$errorCode")
                }
            }
        )
    }

    private fun onOcr(text: Text, gen: Int) {
        if (gen != scrollGen || !isTelegramFg()) {
            scheduleRefresh(300)
            return
        }
        val found = ArrayList<Pair<Rect, String>>()
        var lines = 0
        for (block in text.textBlocks) {
            for (line in block.lines) {
                lines++
                val sb = StringBuilder()
                val spans = ArrayList<Triple<Int, Int, Rect>>()
                for (el in line.elements) {
                    val box = el.boundingBox ?: continue
                    if (sb.isNotEmpty()) sb.append(' ')
                    val st = sb.length
                    sb.append(el.text)
                    spans.add(Triple(st, sb.length, box))
                }
                val lineText = sb.toString()
                if (lineText.count { it.isDigit() } < 9) continue
                for ((range, ocrNumber) in Caller.findNumbers(lineText)) {
                    val number = verifyNumber(ocrNumber) ?: continue
                    val u = Rect()
                    var first = true
                    for ((st, en, box) in spans) {
                        if (en > range.first && st <= range.last) {
                            if (first) {
                                u.set(box)
                                first = false
                            } else {
                                u.union(box)
                            }
                        }
                    }
                    if (first) continue
                    block.boundingBox?.let { bb ->
                        if (bb.left < u.left) u.left = bb.left
                        if (bb.right > u.right) u.right = bb.right
                    }
                    u.inset(-12, -14)
                    if (u.left < 0) u.left = 0
                    if (u.top < 0) u.top = 0
                    if (u.width() > 20 && u.height() > 20) found.add(Pair(u, number))
                }
            }
        }
        saveDump("OCR lines=$lines numbers=${found.size} " +
            found.take(4).joinToString(" | ") { "${it.second}@${it.first.toShortString()}" })
        applyOverlays(found)
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
                            simUntil = now + SIM_MS
                            Toast.makeText(this, "📞 $number", Toast.LENGTH_SHORT).show()
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
        } catch (e: Exception) {
            saveDump("OVERLAY ERR: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun clearOverlays() {
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

        // Тікелей қоңырау жасалып қойса — мәзірді жабамыз, диалерге бармаймыз
        if (System.currentTimeMillis() - lastCallTime < 5000) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return true
        }

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
        if (clickNodeOrParent(target)) {
            pendingDialUntil = 0L
            simUntil = System.currentTimeMillis() + SIM_MS
        }
    }

    /** «Выбор SIM-карты» терезесі шықса — «activ» SIM-ін (табылмаса бірінші SIM-ді) басады. */
    private fun pickSim(): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try { windows?.forEach { w -> w.root?.let { roots.add(it) } } } catch (_: Exception) {}

        for (root in roots) {
            val nodes = ArrayList<AccessibilityNodeInfo>()
            collect(root, nodes, 300)
            val isSimDialog = nodes.any { n ->
                val t = n.text?.toString()?.lowercase() ?: return@any false
                SIM_TITLES.any { t.contains(it) }
            }
            if (!isSimDialog) continue

            nodes.firstOrNull { n ->
                (n.text ?: n.contentDescription)?.toString()?.lowercase()?.contains(SIM_PREFER) == true
            }?.let { pref ->
                if (clickNodeOrParent(pref)) return true
            }

            val r = Rect()
            val options = nodes.filter { n ->
                n.isClickable && n.isEnabled && SIM_SKIP.none { labelOf(n).contains(it) }
            }.sortedBy { n -> n.getBoundsInScreen(r); r.top }
            val target = options.firstOrNull { it.isSelected || it.isChecked } ?: options.firstOrNull()
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            saveDump("SIM dialog: option not clicked")
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
            val list = (listOf(entry) + old.split("\n").filter { it.isNotBlank() }).take(14)
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
