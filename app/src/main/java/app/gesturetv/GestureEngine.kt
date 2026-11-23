package app.gesturetv

import kotlin.math.abs

enum class Action(val label: String) {
    VOL_UP("🔊 +"), VOL_DOWN("🔉 −"), MUTE("🔇"),
    UP("⬆"), DOWN("⬇"), LEFT("⬅"), RIGHT("➡"),
    OK("✊ OK"), BACK("✌ Back"), HOME("🏠 Home"),
    APP2("▶"), APP3("▶"),   // finger-count shortcuts; names come from config.json
    TIMER("⏰"),
}

/** One analysed camera frame. cx/cy = palm centre, normalised 0..1 in image coordinates. */
data class Frame(val t: Long, val label: String, val hand: Boolean, val cx: Float, val cy: Float)

/**
 * Turns a stream of per-frame gesture labels into TV actions.
 *
 * Off until an open palm is held still for armHoldMs; then on for armMs after the last action.
 * Static poses must be held (and the hand kept still) before they fire, which filters
 * out the in-between shapes a hand makes while moving.
 *
 * Drag sliders: 👌 up/down = volume; open palm moved slowly in any direction = arrow keys.
 */
class GestureEngine(
    private val mirror: Boolean,
    private val armHoldMs: Long = 1000,
    private val armMs: Long = 10_000,
    private val onAction: (Action) -> Unit,
) {
    companion object {
        const val SWIPE_WINDOW_MS = 350L
        const val SWIPE_DIST = 0.18f      // fraction of the image width/height
        const val SWIPE_COOLDOWN_MS = 700L
        const val RETURN_STROKE_MS = 1000L
        const val STILL_DIST = 0.04f
        const val VOL_STEP = 0.05f        // hand travel (fraction of image height) per volume step
        const val NAV_STEP = 0.07f        // hand travel (fraction of image width) per tile
        const val NAV_STEP_Y = 0.08f      // same, vertically
        const val FAST_SPEED = 0.85f      // hand speed (frame widths per second) above this is ignored
    }

    @Volatile var armedUntil = 0L
        private set

    fun armed(now: Long) = now < armedUntil

    private val hist = ArrayDeque<Frame>()
    private var stableLabel = "None"
    private var stableSince = 0L
    private var firedForStable = false
    private var lastSwipeT = 0L
    private var lastSwipeTPrev = 0L
    private var lastSwipe: Action? = null
    private var pinchAnchor: Float? = null
    private var pinchX: Float? = null
    private var grabKind: String? = null
    private var pinchEndT = 0L

    fun feed(f: Frame) {
        val now = f.t
        if (!f.hand) {
            hist.clear(); endPinch(now); setLabel("None", now); return
        }
        hist.addLast(f)
        while (hist.isNotEmpty() && now - hist.first().t > 600) hist.removeFirst()
        setLabel(f.label, now)
        val held = now - stableSince

        if (!armed(now)) {
            if (stableLabel == "Open_Palm" && held >= armHoldMs && still(now, 300)) {
                armedUntil = now + armMs
                firedForStable = true
            }
            return
        }

        if (stableLabel == "Open_Palm") armedUntil = maxOf(armedUntil, now + armMs)

        // Drag sliders (hand position relative to where the drag started, one step per STEP travelled):
        //   👌 ("Pinch") up/down = volume
        //   ✋ open palm in any direction = arrow keys
        val kind = when {
            f.label == "Pinch" -> "Pinch"
            stableLabel == "Open_Palm" && (f.label == "Open_Palm" || f.label == "None") -> "Palm"
            else -> null
        }
        if (kind != null) {
            armedUntil = maxOf(armedUntil, now + armMs)
            if (grabKind != kind) { grabKind = kind; pinchX = null; pinchAnchor = null }
            // A fast movement (dropping the hand to rest, waving) is not a drag: re-anchor and ignore it.
            if (hist.size >= 2) {
                val p = hist[hist.size - 2]
                val dt = (f.t - p.t).coerceAtLeast(1L) / 1000f
                if (abs(f.cx - p.cx) / dt > FAST_SPEED || abs(f.cy - p.cy) / dt > FAST_SPEED) { pinchX = f.cx; pinchAnchor = f.cy; return }
            }
            val ax = pinchX; val ay = pinchAnchor
            if (ax == null || ay == null) { pinchX = f.cx; pinchAnchor = f.cy; return }
            val dx = f.cx - ax
            val dy = ay - f.cy                      // image y grows downwards; dy > 0 = hand moved up
            if (kind == "Pinch") {
                pinchX = f.cx
                if (dy > VOL_STEP) { pinchAnchor = ay - VOL_STEP; fire(Action.VOL_UP, now) }
                else if (dy < -VOL_STEP) { pinchAnchor = ay + VOL_STEP; fire(Action.VOL_DOWN, now) }
            } else {
                if (abs(dx) > NAV_STEP && abs(dx) > 1.5f * abs(dy)) {
                    pinchX = ax + (if (dx > 0) NAV_STEP else -NAV_STEP); pinchAnchor = f.cy
                    // The camera faces the viewer: their right is the image's left.
                    fire(if ((dx < 0) == mirror) Action.RIGHT else Action.LEFT, now)
                } else if (abs(dy) > NAV_STEP_Y && abs(dy) > 1.5f * abs(dx)) {
                    pinchAnchor = ay - (if (dy > 0) NAV_STEP_Y else -NAV_STEP_Y); pinchX = f.cx
                    fire(if (dy > 0) Action.UP else Action.DOWN, now)
                }
            }
            return
        }
        endPinch(now)
        if (now - pinchEndT < 400) return           // changing pose after a drag
        if (!still(now, 250)) return

        when (stableLabel) {
            "Thumb_Down" -> once(held, 1000, Action.MUTE, now)
            "Thumb_Up" -> once(held, 2000, Action.TIMER, now)
            "Closed_Fist" -> once(held, 400, Action.OK, now)
            "Pointing_Up" -> once(held, 700, Action.HOME, now)      // 1 finger
            "Victory" -> once(held, 1000, Action.APP2, now)         // 2 fingers
            "Three" -> once(held, 1000, Action.APP3, now)           // 3 fingers
            "ILoveYou" -> once(held, 400, Action.BACK, now)         // 🤟
        }
    }

    private fun endPinch(now: Long) {
        if (pinchAnchor != null) { pinchAnchor = null; pinchX = null; grabKind = null; pinchEndT = now }
    }

    /**
     * The model's per-frame label flickers (a thumbs-up reads as "None" for a frame now and then).
     * Only switch the stable label once a new label has been seen in 3 frames in a row, so a
     * brief flicker doesn't restart a hold timer.
     */
    private var candidate = "None"
    private var candCount = 0
    private var candSince = 0L

    private fun setLabel(label: String, now: Long) {
        if (label == stableLabel) { candCount = 0; return }
        if (label == candidate && candCount > 0) candCount++
        else { candidate = label; candCount = 1; candSince = now }
        if (candCount >= 3) {
            log?.invoke("pose ${stableLabel} -> $label")
            stableLabel = label; stableSince = candSince; firedForStable = false; candCount = 0
        }
    }

    var log: ((String) -> Unit)? = null

    private fun once(held: Long, need: Long, a: Action, now: Long) {
        if (!firedForStable && held >= need) {
            firedForStable = true; fire(a, now)
        }
    }

    private fun fire(a: Action, now: Long) {
        armedUntil = now + armMs
        onAction(a)
    }

    private fun still(now: Long, ms: Long): Boolean {
        val w = hist.filter { now - it.t <= ms }
        if (w.size < 2) return false
        val dx = w.maxOf { it.cx } - w.minOf { it.cx }
        val dy = w.maxOf { it.cy } - w.minOf { it.cy }
        return dx < STILL_DIST && dy < STILL_DIST
    }

    private fun detectSwipe(now: Long): Boolean {
        if (now - lastSwipeT < SWIPE_COOLDOWN_MS) return false
        val w = hist.filter { now - it.t <= SWIPE_WINDOW_MS }
        if (w.size < 3) return false
        val dx = w.last().cx - w.first().cx
        val dy = w.last().cy - w.first().cy
        val ax = abs(dx); val ay = abs(dy)
        val a = when {
            ax > SWIPE_DIST && ax > 2 * ay ->
                // The camera faces the viewer: their right is the image's left.
                if ((dx < 0) == mirror) Action.RIGHT else Action.LEFT
            ay > SWIPE_DIST && ay > 2 * ax -> if (dy < 0) Action.UP else Action.DOWN
            else -> return false
        }
        val prev = lastSwipe
        lastSwipeT = now
        hist.clear()
        // Bringing the hand back after a swipe looks like the opposite swipe: ignore it.
        if (prev != null && now - lastSwipeTPrev < RETURN_STROKE_MS && opposite(prev, a)) {
            lastSwipe = null
            return true
        }
        lastSwipe = a
        lastSwipeTPrev = now
        fire(a, now)
        return true
    }

    private fun opposite(a: Action, b: Action) =
        setOf(a, b) == setOf(Action.LEFT, Action.RIGHT) || setOf(a, b) == setOf(Action.UP, Action.DOWN)
}
