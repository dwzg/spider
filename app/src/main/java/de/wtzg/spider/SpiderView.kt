package de.wtzg.spider

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class SpiderView(context: Context) : View(context) {

    interface Listener {
        fun onGameChanged()
        fun onMessage(text: String)
        fun onWon()
    }

    var listener: Listener? = null

    var game: SpiderGame? = null
        set(value) {
            field = value
            cancelInteraction()
            invalidate()
        }

    // ---- layout ----
    private var cardW = 0f
    private var cardH = 0f
    private var gap = 0f
    private var topY = 0f
    private var tableauY = 0f
    private val colX = FloatArray(SpiderGame.COLUMNS)
    private val stockRect = RectF()

    // ---- paints ----
    private val density = resources.displayMetrics.density
    private val feltPaint = Paint()
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFDFDFD.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF8A8A8A.toInt(); strokeWidth = density
    }
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = density * 1.2f
    }
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x55FFFFFF; strokeWidth = density * 1.5f
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFD54F.toInt(); strokeWidth = density * 3f
    }
    private val rankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val suitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val bigSuitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    // ---- touch / drag ----
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragCol = -1
    private var dragIdx = -1
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var grabDX = 0f
    private var grabDY = 0f
    private var curX = 0f
    private var curY = 0f
    private var stockPressed = false

    // ---- hint ----
    private var hint: Move? = null
    private var hintStock = false
    private var hintCycle = 0
    private val clearHint = Runnable { hint = null; hintStock = false; invalidate() }

    // ---- animation ----
    private class Flying(val card: Card, val col: Int, val idx: Int, val sx: Float, val sy: Float, val delay: Float)
    private var flying: List<Flying> = emptyList()
    private var animTime = 0f // ms since start
    private var animator: ValueAnimator? = null
    private val flyDuration = 200f
    private val interp = DecelerateInterpolator()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val n = SpiderGame.COLUMNS
        gap = w * 0.012f
        cardW = (w - gap * (n + 1)) / n
        cardH = cardW * 1.4f
        val maxH = h * 0.22f
        if (cardH > maxH) { cardH = maxH; cardW = cardH / 1.4f }
        val total = cardW * n + gap * (n - 1)
        val left = (w - total) / 2
        for (i in 0 until n) colX[i] = left + i * (cardW + gap)
        topY = gap
        tableauY = topY + cardH + gap * 2
        rankPaint.textSize = cardW * 0.34f
        suitPaint.textSize = cardW * 0.30f
        bigSuitPaint.textSize = cardW * 0.62f
        labelPaint.textSize = cardW * 0.26f
        feltPaint.shader = RadialGradient(
            w / 2f, h * 0.35f, max(w, h) * 0.8f,
            0xFF13874A.toInt(), 0xFF07502A.toInt(), Shader.TileMode.CLAMP,
        )
        backPaint.shader = LinearGradient(0f, 0f, cardW, cardH, 0xFF2F55C8.toInt(), 0xFF162E7A.toInt(), Shader.TileMode.MIRROR)
    }

    /** y position of each card in a column, squeezing the gaps when the column gets long. */
    private fun offsets(col: List<Card>): FloatArray {
        val ys = FloatArray(col.size)
        if (col.isEmpty()) return ys
        val down = cardH * 0.11f
        val up = cardH * 0.28f
        var need = 0f
        for (i in 0 until col.size - 1) need += if (col[i].faceUp) up else down
        val avail = height - tableauY - cardH - gap
        val scale = if (need > avail && need > 0) avail / need else 1f
        var y = tableauY
        for (i in col.indices) {
            ys[i] = y
            y += (if (col[i].faceUp) up else down) * scale
        }
        return ys
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), feltPaint)
        val g = game ?: return
        drawTopRow(canvas, g)

        val hidden = if (animator != null) flying.map { it.col to it.idx }.toSet() else emptySet()
        for (c in 0 until SpiderGame.COLUMNS) {
            val col = g.columns[c]
            val x = colX[c]
            rect.set(x, tableauY, x + cardW, tableauY + cardH)
            canvas.drawRoundRect(rect, radius(), radius(), slotPaint)
            val ys = offsets(col)
            val end = if (dragging && c == dragCol) dragIdx else col.size
            for (i in 0 until end) {
                if ((c to i) in hidden) continue
                drawCard(canvas, col[i], x, ys[i])
            }
            val h = hint
            if (h != null) {
                if (h.from == c && h.index < col.size) {
                    rect.set(x, ys[h.index], x + cardW, ys[col.lastIndex] + cardH)
                    canvas.drawRoundRect(rect, radius(), radius(), highlightPaint)
                }
                if (h.to == c) {
                    val y = if (col.isEmpty()) tableauY else ys[col.lastIndex]
                    rect.set(x, y, x + cardW, y + cardH)
                    canvas.drawRoundRect(rect, radius(), radius(), highlightPaint)
                }
            }
        }

        if (dragging && dragCol >= 0) {
            val col = g.columns[dragCol]
            val ys = offsets(col)
            val x = curX - grabDX
            val dy = (curY - grabDY) - ys[dragIdx]
            for (i in dragIdx until col.size) drawCard(canvas, col[i], x, ys[i] + dy)
        }

        if (animator != null) {
            for (f in flying) {
                val t = interp.getInterpolation(((animTime - f.delay) / flyDuration).coerceIn(0f, 1f))
                val col = g.columns.getOrNull(f.col) ?: continue
                if (f.idx >= col.size) continue
                val ty = offsets(col)[f.idx]
                val tx = colX[f.col]
                val card = if (t <= 0f && f.delay > 0f) f.card.copy(faceUp = false) else f.card
                drawCard(canvas, card, f.sx + (tx - f.sx) * t, f.sy + (ty - f.sy) * t)
            }
        }
    }

    private fun drawTopRow(canvas: Canvas, g: SpiderGame) {
        // Completed runs, shown as their kings.
        val step = cardW * 0.32f
        for (i in 0 until SpiderGame.DECKS) {
            val x = colX[0] + i * step
            if (i < g.completed.size) {
                drawCard(canvas, Card(g.completed[i], 13, true), x, topY)
            } else if (i == g.completed.size) {
                rect.set(x, topY, x + cardW, topY + cardH)
                canvas.drawRoundRect(rect, radius(), radius(), slotPaint)
            }
        }

        // Stock: one face-down card per remaining deal.
        val deals = g.dealsLeft
        val right = colX[SpiderGame.COLUMNS - 1]
        val sStep = cardW * 0.18f
        stockRect.set(right - 4 * sStep, topY, right + cardW, topY + cardH)
        if (deals == 0) {
            rect.set(right, topY, right + cardW, topY + cardH)
            canvas.drawRoundRect(rect, radius(), radius(), slotPaint)
        }
        for (i in 0 until deals) drawBack(canvas, right - (deals - 1 - i) * sStep, topY)
        if (hintStock) {
            canvas.drawRoundRect(stockRect, radius(), radius(), highlightPaint)
        }
        val mid = (colX[0] + 7 * step + cardW + stockRect.left) / 2
        val labelY = topY + cardH / 2 + labelPaint.textSize / 3
        val suitsName = when (g.suitCount) { 1 -> "1 suit"; 2 -> "2 suits"; else -> "4 suits" }
        if (stockRect.left - (colX[0] + 7 * step + cardW) > cardW * 1.4f) {
            canvas.drawText(suitsName, mid, labelY, labelPaint)
        }
    }

    private fun radius() = cardW * 0.09f

    private fun drawBack(canvas: Canvas, x: Float, y: Float) {
        rect.set(x, y, x + cardW, y + cardH)
        canvas.save()
        canvas.translate(x, y)
        rect.set(0f, 0f, cardW, cardH)
        canvas.drawRoundRect(rect, radius(), radius(), backPaint)
        val inset = cardW * 0.1f
        rect.set(inset, inset, cardW - inset, cardH - inset)
        canvas.drawRoundRect(rect, radius() * 0.6f, radius() * 0.6f, backInner)
        rect.set(0f, 0f, cardW, cardH)
        canvas.drawRoundRect(rect, radius(), radius(), borderPaint)
        canvas.restore()
    }

    private fun drawCard(canvas: Canvas, card: Card, x: Float, y: Float) {
        if (!card.faceUp) { drawBack(canvas, x, y); return }
        rect.set(x, y, x + cardW, y + cardH)
        canvas.drawRoundRect(rect, radius(), radius(), facePaint)
        canvas.drawRoundRect(rect, radius(), radius(), borderPaint)
        val color = if (card.isRed) 0xFFD32F2F.toInt() else 0xFF1B1B1B.toInt()
        rankPaint.color = color
        suitPaint.color = color
        bigSuitPaint.color = color
        val pad = cardW * 0.07f
        val label = RANKS[card.rank]
        rankPaint.textScaleX = if (label.length > 1) 0.8f else 1f
        canvas.drawText(label, x + pad, y + rankPaint.textSize * 0.95f, rankPaint)
        canvas.drawText(SUITS[card.suit], x + cardW - pad, y + rankPaint.textSize * 0.92f, suitPaint)
        canvas.drawText(SUITS[card.suit], x + cardW / 2, y + cardH * 0.78f, bigSuitPaint)
    }

    // ---------------------------------------------------------------- input

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val g = game ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishAnimation()
                downX = e.x; downY = e.y
                dragging = false; dragCol = -1; stockPressed = false
                if (stockRect.contains(e.x, e.y)) { stockPressed = true; return true }
                val hit = hitTest(e.x, e.y) ?: return true
                if (g.canPickUp(hit.first, hit.second)) {
                    dragCol = hit.first
                    dragIdx = hit.second
                    val ys = offsets(g.columns[dragCol])
                    grabDX = e.x - colX[dragCol]
                    grabDY = e.y - ys[dragIdx]
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragCol >= 0) {
                    if (!dragging && hypot(e.x - downX, e.y - downY) > touchSlop) dragging = true
                    if (dragging) { curX = e.x; curY = e.y; invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) performClick()
                if (stockPressed) {
                    if (stockRect.contains(e.x, e.y)) dealTapped()
                } else if (dragCol >= 0) {
                    if (dragging) {
                        val to = dropTarget(g)
                        if (to >= 0) applyMove(Move(dragCol, dragIdx, to), animate = false)
                    } else {
                        val to = g.bestTarget(dragCol, dragIdx)
                        if (to != null) applyMove(Move(dragCol, dragIdx, to), animate = true)
                        else performHapticFeedback(
                            if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
                            else HapticFeedbackConstants.LONG_PRESS,
                        )
                    }
                }
                cancelInteraction()
            }
            MotionEvent.ACTION_CANCEL -> cancelInteraction()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun cancelInteraction() {
        dragging = false; dragCol = -1; dragIdx = -1; stockPressed = false
        invalidate()
    }

    private fun hitTest(x: Float, y: Float): Pair<Int, Int>? {
        val g = game ?: return null
        for (c in 0 until SpiderGame.COLUMNS) {
            if (x < colX[c] - gap / 2 || x > colX[c] + cardW + gap / 2) continue
            val col = g.columns[c]
            val ys = offsets(col)
            for (i in col.indices.reversed()) {
                if (y >= ys[i] && y <= ys[i] + cardH) return c to i
            }
        }
        return null
    }

    private fun dropTarget(g: SpiderGame): Int {
        val left = curX - grabDX
        val card = g.columns[dragCol][dragIdx]
        var best = -1
        var bestOverlap = 0f
        for (c in 0 until SpiderGame.COLUMNS) {
            if (c == dragCol || !g.canDrop(card, c)) continue
            val overlap = cardW - abs(colX[c] - left)
            if (overlap > bestOverlap) { bestOverlap = overlap; best = c }
        }
        return best
    }

    // ---------------------------------------------------------------- actions

    private fun applyMove(m: Move, animate: Boolean) {
        val g = game ?: return
        val src = g.columns[m.from]
        val ys = offsets(src)
        val starts = (m.index until src.size).map { Triple(src[it], colX[m.from], ys[it]) }
        val destStart = g.columns[m.to].size
        if (!g.move(m)) return
        if (animate && g.columns[m.to].size == destStart + starts.size) {
            startAnimation(starts.mapIndexed { i, (card, sx, sy) -> Flying(card, m.to, destStart + i, sx, sy, 0f) })
        }
        changed()
    }

    private fun dealTapped() {
        val g = game ?: return
        if (g.stock.isEmpty()) { listener?.onMessage("No more cards to deal"); return }
        if (!g.canDeal()) { listener?.onMessage("Fill every empty column before dealing"); return }
        val sx = colX[SpiderGame.COLUMNS - 1]
        val before = g.columns.map { it.size }
        g.deal()
        val fl = ArrayList<Flying>()
        for (c in 0 until SpiderGame.COLUMNS) {
            val col = g.columns[c]
            if (col.size == before[c] + 1) fl.add(Flying(col.last(), c, col.lastIndex, sx, topY, c * 35f))
        }
        startAnimation(fl)
        changed()
    }

    fun undo() {
        val g = game ?: return
        finishAnimation()
        if (g.undo()) changed() else listener?.onMessage("Nothing to undo")
    }

    fun showHint() {
        val g = game ?: return
        val moves = g.hints()
        removeCallbacks(clearHint)
        if (moves.isEmpty()) {
            hint = null
            if (g.canDeal()) { hintStock = true; listener?.onMessage("No moves — deal from the stock") }
            else listener?.onMessage("No useful moves left")
        } else {
            hintStock = false
            hint = moves[hintCycle % moves.size]
            hintCycle++
        }
        postDelayed(clearHint, 2000)
        invalidate()
    }

    private fun changed() {
        removeCallbacks(clearHint)
        hint = null; hintStock = false; hintCycle = 0
        invalidate()
        listener?.onGameChanged()
        if (game?.isWon == true) listener?.onWon()
    }

    private fun startAnimation(items: List<Flying>) {
        finishAnimation()
        if (items.isEmpty()) return
        flying = items
        val total = flyDuration + (items.maxOf { it.delay })
        animator = ValueAnimator.ofFloat(0f, total).apply {
            duration = total.toLong()
            addUpdateListener { animTime = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) { animator = null; flying = emptyList(); invalidate() }
                }
            })
            start()
        }
        animTime = 0f
    }

    private fun finishAnimation() {
        val a = animator ?: return
        animator = null
        flying = emptyList()
        a.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        finishAnimation()
        removeCallbacks(clearHint)
    }

    companion object {
        private val RANKS = arrayOf("", "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
        private val SUITS = arrayOf("♠", "♥", "♣", "♦")
    }
}
