package be.verdonckit.zapper.core

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import kotlin.random.Random

/**
 * Inactiviteit op TV, in drie fasen:
 * 1. actief: het scherm blijft aan (FLAG_KEEP_SCREEN_ON), zodat de systeem-screensaver ons niet voor is;
 * 2. na [screensaverAfterMs] zonder toets: eigen OLED-veilig scherm (zwart, gedimde klok die verspringt);
 * 3. na nog [SLEEP_AFTER_SCREENSAVER_MS]: het scherm loslaten, zodat de TV zelf gaat slapen.
 *
 * Zolang [blocksIdle] true is (bv. video speelt), telt inactiviteit niet.
 * De eerste toets tijdens de screensaver sluit die enkel (ook "terug" doet dan niets anders).
 */
class IdleController(
    private val activity: Activity,
    private val screensaverAfterMs: () -> Long,
    private val blocksIdle: () -> Boolean = { false },
    private val caption: () -> String? = { null },
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastInteraction = SystemClock.uptimeMillis()
    private var saver: ScreensaverView? = null
    private var saverSince = 0L
    private var swallowKeyUp = false
    private var keepOn = false

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** In onResume. */
    fun start() {
        lastInteraction = SystemClock.uptimeMillis()
        setKeepScreenOn(true)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** In onPause. */
    fun stop() {
        handler.removeCallbacks(tick)
        hide()
    }

    /**
     * Elke toets komt hierlangs (vanuit dispatchKeyEvent). true = opgegeten: de screensaver
     * werd gesloten en de app mag niets met deze toets doen.
     */
    fun handleKey(event: KeyEvent): Boolean {
        lastInteraction = SystemClock.uptimeMillis()
        if (saver != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                hide()
                swallowKeyUp = true
                setKeepScreenOn(true)
            }
            return true
        }
        if (swallowKeyUp && event.action == KeyEvent.ACTION_UP) {
            swallowKeyUp = false
            return true
        }
        if (!keepOn) setKeepScreenOn(true)
        return false
    }

    private fun evaluate() {
        val now = SystemClock.uptimeMillis()
        if (blocksIdle()) {
            lastInteraction = now
            hide()
            setKeepScreenOn(true)
            return
        }
        if (saver == null && now - lastInteraction >= screensaverAfterMs()) show(now)
        saver?.let {
            it.update(caption())
            if (now - saverSince >= SLEEP_AFTER_SCREENSAVER_MS) setKeepScreenOn(false)
        }
    }

    private fun show(now: Long) {
        val root = activity.window.decorView as ViewGroup
        saver = ScreensaverView(activity).also {
            root.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            it.update(caption())
            it.alpha = 0f
            it.animate().alpha(1f).setDuration(1_500).start()
        }
        saverSince = now
    }

    private fun hide() {
        saver?.let { (it.parent as? ViewGroup)?.removeView(it) }
        saver = null
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on == keepOn) return
        keepOn = on
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val TICK_MS = 5_000L
        const val NAVIGATION_SCREENSAVER_MS = 5 * 60_000L
        const val PAUSED_SCREENSAVER_MS = 3 * 60_000L
        const val SLEEP_AFTER_SCREENSAVER_MS = 15 * 60_000L
    }
}

/**
 * Zwart scherm met een kleine, gedimde klok. Verspringt elke 45 s naar een willekeurige
 * plek, zodat geen enkele pixel lang hetzelfde toont (OLED-inbranding).
 */
private class ScreensaverView(activity: Activity) : FrameLayout(activity) {

    private val clock = TextClock(activity).apply {
        format24Hour = "HH:mm"
        format12Hour = "HH:mm"
        textSize = 64f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        setTextColor(0xFF7A7A7A.toInt())
    }
    private val brand = TextView(activity).apply {
        text = "ZAPPER"
        textSize = 14f
        letterSpacing = 0.3f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF6B0A10.toInt())
    }
    private val captionView = TextView(activity).apply {
        textSize = 16f
        setTextColor(0xFF5A5A5A.toInt())
        maxLines = 1
        visibility = View.GONE
    }
    private val block = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        addView(brand)
        addView(clock)
        addView(captionView)
    }
    private val move = object : Runnable {
        override fun run() {
            reposition()
            postDelayed(this, 45_000)
        }
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        isClickable = true
        addView(block, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (block.x == 0f && block.y == 0f) reposition() }
    }

    fun update(caption: String?) {
        captionView.text = caption.orEmpty()
        captionView.visibility = if (caption.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun reposition() {
        val maxX = (width - block.width).coerceAtLeast(1)
        val maxY = (height - block.height).coerceAtLeast(1)
        block.animate().alpha(0f).setDuration(600).withEndAction {
            block.x = Random.nextInt(maxX).toFloat().coerceAtLeast(1f)
            block.y = Random.nextInt(maxY).toFloat().coerceAtLeast(1f)
            block.animate().alpha(1f).setDuration(600).start()
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(move, 45_000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(move)
        super.onDetachedFromWindow()
    }
}
