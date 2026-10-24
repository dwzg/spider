package de.wtzg.spider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the board and handles input.
 *
 * Animations work by diffing: before every change we record where each card is on screen,
 * apply the change, then fly every card whose position or face changed to its new spot.
 * That one mechanism covers moves, deals, undo, completed runs and the opening deal.
 */
class SpiderView(context: Context) : View(context) {

    interface Listener {
        fun onGameChanged()
        fun onMessage(text: String)
        fun onWon()
        fun onCelebrationFinished()
    }

    var listener: Listener? = null

    var game: SpiderGame? = null
        private set

    /** Space taken by the status bar; the board draws behind it but keeps cards below it. */
    var topInset = 0
        set(value) {
            field = value
            if (width > 0) computeLayout(width, height)
            invalidate()
        }

    fun setGame(g: SpiderGame, dealAnimation: Boolean) {
        stopCelebration(notify = false)
        anims.clear()
        cancelInteraction()
        hint = null; hintStock = false; hintCycle = 0
        game = g
        celebrationPending = false
        if (dealAnimation) {
            if (width > 0) animateNewGame() else pendingDeal = true
        }
        invalidate()
    }

    // ------------------------------------------------------------------ layout

    private var cardW = 0f
    private var cardH = 0f
    private var gap = 0f
    private var topY = 0f
    private var tableauY = 0f
    private var radius = 0f
    private val colX = FloatArray(SpiderGame.COLUMNS)
    private val stockRect = RectF()
    private var pendingDeal = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeLayout(w, h)
        anims.clear()
        if (pendingDeal) { pendingDeal = false; animateNewGame() }
    }

    private fun computeLayout(w: Int, h: Int) {
        val n = SpiderGame.COLUMNS
        gap = w * 0.012f
        cardW = (w - gap * (n + 1)) / n
        cardH = cardW * 1.4f
        val maxH = (h - topInset) * 0.21f
        if (cardH > maxH) { cardH = maxH; cardW = cardH / 1.4f }
        radius = cardW * 0.1f
        val total = cardW * n + gap * (n - 1)
        val left = (w - total) / 2
        for (i in 0 until n) colX[i] = left + i * (cardW + gap)
        topY = topInset + gap * 1.5f
        tableauY = topY + cardH + gap * 2.5f

        rankPaint.textSize = cardW * 0.36f
        courtPaint.textSize = cardW * 0.5f
        labelPaint.textSize = max(cardW * 0.22f, 10f * density)
        feltPaint.shader = RadialGradient(
            w / 2f, h * 0.3f, max(w, h) * 0.85f,
            intArrayOf(0xFF1A8F52.toInt(), 0xFF0C6B38.toInt(), 0xFF053D20.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        buildBitmaps()
    }

    private fun stockX(group: Int, groups: Int): Float =
        colX[SpiderGame.COLUMNS - 1] - (groups - 1 - group) * cardW * 0.2f

    private fun foundationX(k: Int): Float = colX[0] + k * cardW * 0.3f

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

    // ------------------------------------------------------------------ card positions

    private enum class Zone { STOCK, TABLEAU, FOUNDATION }

    private class Slot(
        val x: Float, val y: Float, val card: Card, val zone: Zone,
        val col: Int = -1, val index: Int = -1,
    )

    /** Every card's resting place for the current game state, in drawing order. */
    private fun snapshot(g: SpiderGame): LinkedHashMap<Int, Slot> {
        val m = LinkedHashMap<Int, Slot>(128)
        val groups = (g.stock.size + 9) / 10
        for (i in g.stock.indices) {
            m[g.stock[i].id] = Slot(stockX(i / 10, groups), topY, g.stock[i], Zone.STOCK)
        }
        for (k in g.completed.indices) {
            val ids = g.completedIds[k]
            for (j in ids.indices.reversed()) { // ace at the bottom, king on top
                m[ids[j]] = Slot(foundationX(k), topY, Card(g.completed[k], 13 - j, true, ids[j]), Zone.FOUNDATION, k, j)
            }
        }
        for (c in 0 until SpiderGame.COLUMNS) {
            val col = g.columns[c]
            val ys = offsets(col)
            for (i in col.indices) m[col[i].id] = Slot(colX[c], ys[i], col[i], Zone.TABLEAU, c, i)
        }
        return m
    }

    // ------------------------------------------------------------------ animation

    private class Anim(val from: Slot, val to: Slot, val start: Long, val duration: Long, val lift: Boolean)

    private val anims = LinkedHashMap<Int, Anim>()
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val moveMs = 260L

    private fun now() = SystemClock.uptimeMillis()

    private fun progress(a: Anim, t: Long) = ((t - a.start).toFloat() / a.duration).coerceIn(0f, 1f)

    private fun currentSlot(a: Anim, t: Long): Slot {
        val e = ease.getInterpolation(progress(a, t))
        val card = if (e < 0.5f) a.from.card else a.to.card
        return Slot(a.from.x + (a.to.x - a.from.x) * e, a.from.y + (a.to.y - a.from.y) * e, card, a.to.zone, a.to.col, a.to.index)
    }

    /** Where cards are on screen right now, including ones still in flight. */
    private fun visualSlots(g: SpiderGame): HashMap<Int, Slot> {
        val m = HashMap<Int, Slot>(snapshot(g))
        val t = now()
        for ((id, a) in anims) m[id] = currentSlot(a, t)
        return m
    }

    /** Applies [change] and animates every card from where it was to where it ends up. */
    private fun transition(overrides: Map<Int, Slot> = emptyMap(), change: () -> Boolean): Boolean {
        val g = game ?: return false
        val before = visualSlots(g)
        before.putAll(overrides)
        val ok = change()
        val after = snapshot(g)
        planAnims(before, after)
        invalidate()
        return ok
    }

    private fun planAnims(before: Map<Int, Slot>, after: LinkedHashMap<Int, Slot>) {
        val t0 = now()
        class P(val id: Int, val b: Slot, val a: Slot, var delay: Long, val dur: Long, val moved: Boolean)
        val first = ArrayList<P>()
        val collect = ArrayList<P>()
        val flips = ArrayList<P>()
        for ((id, a) in after) {
            val b = before[id] ?: continue
            val moved = abs(a.x - b.x) > 0.5f || abs(a.y - b.y) > 0.5f
            val flipped = a.card.faceUp != b.card.faceUp
            if (!moved && !flipped) continue
            when {
                b.zone != Zone.FOUNDATION && a.zone == Zone.FOUNDATION ->
                    collect.add(P(id, b, a, (a.card.rank - 1) * 30L, 320, true)) // ace leaves first
                b.zone == Zone.FOUNDATION && a.zone != Zone.FOUNDATION ->
                    first.add(P(id, b, a, (13 - b.card.rank) * 22L, 280, true))
                b.zone == Zone.STOCK && a.zone == Zone.TABLEAU ->
                    first.add(P(id, b, a, a.col * 45L, 280, true))
                b.zone == Zone.TABLEAU && a.zone == Zone.STOCK ->
                    first.add(P(id, b, a, (SpiderGame.COLUMNS - 1 - b.col) * 30L, 240, true))
                !moved -> flips.add(P(id, b, a, 0, 220, false))
                else -> first.add(P(id, b, a, 0, moveMs, true))
            }
        }
        val firstEnd = first.maxOfOrNull { it.delay + it.dur } ?: 0L
        val collectStart = (firstEnd * 0.85f).toLong()
        collect.forEach { it.delay += collectStart }
        val collectEnd = collect.maxOfOrNull { it.delay + it.dur } ?: firstEnd
        val flipStart = (max(firstEnd, collectEnd) * 0.75f).toLong()
        flips.forEach { it.delay += flipStart }

        anims.clear()
        (first + collect + flips).sortedBy { it.delay }.forEach {
            anims[it.id] = Anim(it.b, it.a, t0 + it.delay, it.dur, it.moved)
        }
    }

    private fun animateNewGame() {
        val g = game ?: return
        val after = snapshot(g)
        val t0 = now()
        anims.clear()
        val sx = colX[SpiderGame.COLUMNS - 1]
        val dealt = after.values.filter { it.zone == Zone.TABLEAU }.sortedBy { it.index * 10 + it.col }
        dealt.forEachIndexed { order, a ->
            val from = Slot(sx, topY, a.card.copy(faceUp = false), Zone.STOCK)
            anims[a.card.id] = Anim(from, a, t0 + 120 + order * 16L, 300, true)
        }
    }

    // ------------------------------------------------------------------ paints & bitmaps

    private val density = resources.displayMetrics.density
    private val feltPaint = Paint()
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x33000000; strokeWidth = density * 0.8f
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val slotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000 }
    private val slotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x40FFFFFF; strokeWidth = density * 1.2f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val courtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val courtFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val courtStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val suitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.08f
    }
    private val rect = RectF()

    private var backBitmap: Bitmap? = null
    private var shadowBitmap: Bitmap? = null
    private var shadowPad = 0f

    private fun buildBitmaps() {
        val w = max(1, cardW.toInt()); val h = max(1, cardH.toInt())
        backBitmap?.recycle()
        backBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            val r = radius
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF3557C9.toInt(), 0xFF1A2C78.toInt(), Shader.TileMode.CLAMP)
            rect.set(0f, 0f, w.toFloat(), h.toFloat())
            c.drawRoundRect(rect, r, r, p)
            // Diamond lattice inside a white frame.
            val inset = w * 0.09f
            rect.set(inset, inset, w - inset, h - inset)
            c.save()
            val clip = Path().apply { addRoundRect(rect, r * 0.5f, r * 0.5f, Path.Direction.CW) }
            c.clipPath(clip)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF; strokeWidth = max(1f, w * 0.018f) }
            val step = w * 0.16f
            var d = -h.toFloat()
            while (d < w + h) {
                c.drawLine(d, 0f, d + h, h.toFloat(), line)
                c.drawLine(d + h, 0f, d, h.toFloat(), line)
                d += step
            }
            c.restore()
            val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = 0xB3FFFFFF.toInt(); strokeWidth = max(1f, w * 0.025f)
            }
            c.drawRoundRect(rect, r * 0.5f, r * 0.5f, frame)
            // Small emblem in the middle.
            val e = w * 0.34f
            val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A2C78.toInt() }
            c.drawCircle(w / 2f, h / 2f, e * 0.62f, disc)
            c.drawCircle(w / 2f, h / 2f, e * 0.62f, frame)
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
            drawSuit(c, 0, w / 2f, h / 2f, e * 0.7f, sp)
        }
        shadowPad = cardW * 0.12f
        shadowBitmap?.recycle()
        shadowBitmap = Bitmap.createBitmap((w + shadowPad * 2).toInt(), (h + shadowPad * 2).toInt(), Bitmap.Config.ALPHA_8).also { bmp ->
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF000000.toInt()
                maskFilter = BlurMaskFilter(shadowPad * 0.6f, BlurMaskFilter.Blur.NORMAL)
            }
            rect.set(shadowPad, shadowPad, shadowPad + w, shadowPad + h)
            c.drawRoundRect(rect, radius, radius, p)
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), feltPaint)
        val g = game ?: return
        val t = now()

        if (celebrating) {
            drawCelebration(canvas, t)
            postInvalidateOnAnimation()
            return
        }

        // Finished animations drop out.
        if (anims.isNotEmpty() && anims.values.all { t >= it.start + it.duration }) anims.clear()

        drawSlots(canvas, g)
        val slots = snapshot(g)
        val dragIds = dragIds(g)
        val shake = shakeOffset(t)

        for ((id, s) in slots) {
            if (id in anims || id in dragIds) continue
            if (s.zone == Zone.FOUNDATION && s.index != 0) continue // only kings show
            val dx = if (shake != 0f && s.zone == Zone.TABLEAU && s.col == shakeCol && s.index >= shakeIdx) shake else 0f
            drawCard(canvas, s.card, s.x + dx, s.y, shadowAlpha = if (s.zone == Zone.STOCK) 60 else 90)
        }

        drawHint(canvas, g, t)
        drawDropTarget(canvas, g)

        for (a in anims.values) {
            val p = progress(a, t)
            val e = ease.getInterpolation(p)
            val x = a.from.x + (a.to.x - a.from.x) * e
            val y = a.from.y + (a.to.y - a.from.y) * e
            val flipping = a.from.card.faceUp != a.to.card.faceUp
            val card = if (flipping && e < 0.5f) a.from.card else a.to.card
            val scaleX = if (flipping) abs(1f - 2f * e).coerceAtLeast(0.02f) else 1f
            val lift = if (a.lift && p > 0f) sin(PI * e).toFloat() else 0f
            canvas.save()
            val s = 1f + 0.06f * lift
            canvas.scale(scaleX * s, s, x + cardW / 2, y + cardH / 2)
            drawCard(canvas, card, x, y, shadowAlpha = (90 + 70 * lift).toInt(), shadowDrop = lift * cardW * 0.08f)
            canvas.restore()
        }

        if (dragging && dragCol >= 0) {
            val col = g.columns[dragCol]
            val ys = offsets(col)
            val x = curX - grabDX
            val dy = (curY - grabDY) - ys[dragIdx]
            canvas.save()
            canvas.scale(1.05f, 1.05f, curX, curY)
            for (i in dragIdx until col.size) {
                drawCard(canvas, col[i], x, ys[i] + dy, shadowAlpha = 150, shadowDrop = cardW * 0.12f)
            }
            canvas.restore()
        }

        if (celebrationPending && anims.isEmpty()) {
            celebrationPending = false
            startCelebration()
        }

        val motion = anims.isNotEmpty() || hint != null || hintStock || shake != 0f || t < shakeStart + SHAKE_MS
        if (motion || celebrationPending) postInvalidateOnAnimation()
    }

    private fun drawSlots(canvas: Canvas, g: SpiderGame) {
        for (c in 0 until SpiderGame.COLUMNS) {
            drawSlot(canvas, colX[c], tableauY)
        }
        if (g.completed.size < SpiderGame.DECKS) drawSlot(canvas, foundationX(g.completed.size), topY)
        val groups = (g.stock.size + 9) / 10
        stockRect.set(stockX(0, max(groups, 1)) - gap, topY - gap, colX[SpiderGame.COLUMNS - 1] + cardW + gap, topY + cardH + gap)
        if (groups == 0) drawSlot(canvas, colX[SpiderGame.COLUMNS - 1], topY)

        // Difficulty label between the completed runs and the stock.
        val leftEdge = foundationX(SpiderGame.DECKS - 1) + cardW
        val rightEdge = stockX(0, 5)
        if (rightEdge - leftEdge > cardW * 1.6f) {
            val label = when (g.suitCount) { 1 -> "1 SUIT"; 2 -> "2 SUITS"; else -> "4 SUITS" }
            canvas.drawText(label, (leftEdge + rightEdge) / 2, topY + cardH / 2 + labelPaint.textSize * 0.35f, labelPaint)
        }
    }

    private fun drawSlot(canvas: Canvas, x: Float, y: Float) {
        rect.set(x, y, x + cardW, y + cardH)
        canvas.drawRoundRect(rect, radius, radius, slotFill)
        canvas.drawRoundRect(rect, radius, radius, slotStroke)
    }

    private fun drawShadow(canvas: Canvas, x: Float, y: Float, alpha: Int, drop: Float) {
        val sb = shadowBitmap ?: return
        shadowPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(sb, x - shadowPad, y - shadowPad + cardW * 0.025f + drop, shadowPaint)
    }

    private fun drawCard(
        canvas: Canvas, card: Card, x: Float, y: Float,
        shadowAlpha: Int = 90, shadowDrop: Float = 0f,
    ) {
        if (shadowAlpha > 0) drawShadow(canvas, x, y, shadowAlpha, shadowDrop)
        if (!card.faceUp) {
            backBitmap?.let { canvas.drawBitmap(it, x, y, bitmapPaint) }
            return
        }
        rect.set(x, y, x + cardW, y + cardH)
        facePaint.color = 0xFFFFFDF8.toInt()
        canvas.drawRoundRect(rect, radius, radius, facePaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val color = if (card.isRed) 0xFFD0312D.toInt() else 0xFF1E2230.toInt()
        rankPaint.color = color
        suitPaint.color = color
        drawIndex(canvas, card, x, y)
        canvas.save()
        canvas.rotate(180f, x + cardW / 2, y + cardH / 2)
        drawIndex(canvas, card, x, y)
        canvas.restore()

        val cx = x + cardW / 2
        when {
            card.rank > 10 -> {
                // Court card: tinted panel with a big letter.
                val fx = cardW * 0.2f; val top = cardH * 0.3f
                rect.set(x + fx, y + top, x + cardW - fx, y + cardH - top * 0.75f)
                courtFill.color = if (card.isRed) 0xFFFCE8E6.toInt() else 0xFFE6E9F5.toInt()
                courtStroke.color = color and 0x66FFFFFF
                courtStroke.strokeWidth = cardW * 0.02f
                canvas.drawRoundRect(rect, radius * 0.5f, radius * 0.5f, courtFill)
                canvas.drawRoundRect(rect, radius * 0.5f, radius * 0.5f, courtStroke)
                courtPaint.color = color
                canvas.drawText(RANKS[card.rank], cx, rect.top + rect.height() * 0.6f, courtPaint)
                drawSuit(canvas, card.suit, cx, rect.bottom - cardW * 0.115f, cardW * 0.15f, suitPaint)
            }
            card.rank == 1 -> drawSuit(canvas, card.suit, cx, y + cardH * 0.58f, cardW * 0.62f, suitPaint)
            else -> drawSuit(canvas, card.suit, cx, y + cardH * 0.6f, cardW * 0.46f, suitPaint)
        }
    }

    /** Rank in the top-left corner and a small suit at the top-right, readable when stacked. */
    private fun drawIndex(canvas: Canvas, card: Card, x: Float, y: Float) {
        val pad = cardW * 0.08f
        val label = RANKS[card.rank]
        rankPaint.textScaleX = if (label.length > 1) 0.82f else 1f
        val base = y + pad * 0.4f + rankPaint.textSize * 0.82f
        canvas.drawText(label, x + pad, base, rankPaint)
        val s = cardW * 0.26f
        drawSuit(canvas, card.suit, x + cardW - pad - s / 2, base - rankPaint.textSize * 0.36f, s, suitPaint)
    }

    private fun drawSuit(canvas: Canvas, suit: Int, cx: Float, cy: Float, size: Float, paint: Paint) {
        canvas.save()
        canvas.translate(cx - size / 2, cy - size / 2)
        canvas.scale(size / 24f, size / 24f)
        canvas.drawPath(SUIT_PATHS[suit], paint)
        canvas.restore()
    }

    private fun drawGlow(canvas: Canvas, r: RectF, alpha: Float, color: Int = 0xFFFFD54F.toInt()) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        glowPaint.color = color
        glowPaint.alpha = a / 3
        glowPaint.strokeWidth = density * 7f
        canvas.drawRoundRect(r, radius, radius, glowPaint)
        glowPaint.alpha = a
        glowPaint.strokeWidth = density * 2.5f
        canvas.drawRoundRect(r, radius, radius, glowPaint)
    }

    private fun drawHint(canvas: Canvas, g: SpiderGame, t: Long) {
        if (hint == null && !hintStock) return
        val age = t - hintStart
        if (age > HINT_MS) { hint = null; hintStock = false; return }
        val pulse = 0.55f + 0.45f * sin(age / 1000f * 2f * PI.toFloat() * 1.6f).let { (it + 1f) / 2f }
        val fade = min(1f, (HINT_MS - age) / 300f)
        val alpha = pulse * fade
        if (hintStock) {
            val groups = (g.stock.size + 9) / 10
            if (groups > 0) {
                rect.set(stockX(0, groups), topY, colX[SpiderGame.COLUMNS - 1] + cardW, topY + cardH)
                drawGlow(canvas, rect, alpha)
            }
        }
        val h = hint ?: return
        val src = g.columns[h.from]
        if (h.index >= src.size) return
        val ys = offsets(src)
        rect.set(colX[h.from], ys[h.index], colX[h.from] + cardW, ys[src.lastIndex] + cardH)
        drawGlow(canvas, rect, alpha)
        val dst = g.columns[h.to]
        val y = if (dst.isEmpty()) tableauY else offsets(dst)[dst.lastIndex]
        rect.set(colX[h.to], y, colX[h.to] + cardW, y + cardH)
        drawGlow(canvas, rect, alpha)
    }

    private fun drawDropTarget(canvas: Canvas, g: SpiderGame) {
        if (!dragging || hoverCol < 0) return
        val dst = g.columns[hoverCol]
        val y = if (dst.isEmpty()) tableauY else offsets(dst)[dst.lastIndex]
        rect.set(colX[hoverCol], y, colX[hoverCol] + cardW, y + cardH)
        drawGlow(canvas, rect, 0.9f, 0xFFFFFFFF.toInt())
    }

    // ------------------------------------------------------------------ input

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var dragCol = -1
    private var dragIdx = -1
    private var dragging = false
    private var hoverCol = -1
    private var downX = 0f
    private var downY = 0f
    private var grabDX = 0f
    private var grabDY = 0f
    private var curX = 0f
    private var curY = 0f
    private var stockPressed = false

    private fun dragIds(g: SpiderGame): Set<Int> {
        if (!dragging || dragCol < 0) return emptySet()
        val col = g.columns[dragCol]
        return (dragIdx until col.size).mapTo(HashSet()) { col[it].id }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val g = game ?: return false
        if (celebrating) {
            if (e.actionMasked == MotionEvent.ACTION_UP) { performClick(); stopCelebration(notify = true) }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                dragging = false; dragCol = -1; stockPressed = false; hoverCol = -1
                if (stockRect.contains(e.x, e.y) && g.stock.isNotEmpty()) { stockPressed = true; return true }
                val hit = hitTest(e.x, e.y) ?: return true
                if (g.canPickUp(hit.first, hit.second)) {
                    dragCol = hit.first
                    dragIdx = hit.second
                    val ys = offsets(g.columns[dragCol])
                    grabDX = e.x - colX[dragCol]
                    grabDY = e.y - ys[dragIdx]
                } else if (g.columns[hit.first][hit.second].faceUp) {
                    // Tapped inside a mixed run: nudge to show it can't move.
                    startShake(hit.first, hit.second)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragCol >= 0) {
                    if (!dragging && hypot(e.x - downX, e.y - downY) > touchSlop) {
                        dragging = true
                        dragIds(g).forEach { anims.remove(it) }
                    }
                    if (dragging) {
                        curX = e.x; curY = e.y
                        hoverCol = dropTarget(g)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) performClick()
                if (stockPressed) {
                    if (stockRect.contains(e.x, e.y)) dealTapped()
                } else if (dragCol >= 0) {
                    if (dragging) dropDragged(g) else tapped(g)
                }
                cancelInteraction()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settleDragged(g, null)
                cancelInteraction()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Screen centre of the top strip of a tableau card (used by screenshot tests). */
    internal fun cardTouchPoint(col: Int, index: Int): android.graphics.PointF {
        val ys = offsets(game!!.columns[col])
        return android.graphics.PointF(colX[col] + cardW / 2, ys[index] + cardH * 0.1f)
    }

    private fun cancelInteraction() {
        dragging = false; dragCol = -1; dragIdx = -1; stockPressed = false; hoverCol = -1
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
        var bestOverlap = cardW * 0.25f
        for (c in 0 until SpiderGame.COLUMNS) {
            if (c == dragCol || !g.canDrop(card, c)) continue
            val overlap = cardW - abs(colX[c] - left)
            if (overlap > bestOverlap) { bestOverlap = overlap; best = c }
        }
        return best
    }

    // ------------------------------------------------------------------ actions

    private fun tapped(g: SpiderGame) {
        val to = g.bestTarget(dragCol, dragIdx)
        if (to == null) {
            startShake(dragCol, dragIdx)
            return
        }
        val m = Move(dragCol, dragIdx, to)
        if (transition { g.move(m) }) changed()
    }

    private fun dropDragged(g: SpiderGame) {
        val to = dropTarget(g)
        settleDragged(g, if (to >= 0) Move(dragCol, dragIdx, to) else null)
    }

    /** Animate dragged cards from the finger to their new home, or back where they came from. */
    private fun settleDragged(g: SpiderGame, m: Move?) {
        val col = g.columns[dragCol]
        val ys = offsets(col)
        val x = curX - grabDX
        val dy = (curY - grabDY) - ys[dragIdx]
        val overrides = HashMap<Int, Slot>()
        for (i in dragIdx until col.size) {
            overrides[col[i].id] = Slot(x, ys[i] + dy, col[i], Zone.TABLEAU, dragCol, i)
        }
        val moved = transition(overrides) { m != null && g.move(m) }
        if (moved) changed()
        else if (m == null) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun dealTapped() {
        val g = game ?: return
        if (g.stock.isEmpty()) return
        if (!g.canDeal()) {
            listener?.onMessage("Fill every empty column before dealing")
            performHapticFeedback(rejectFeedback())
            return
        }
        if (transition { g.deal() }) changed()
    }

    fun undo() {
        val g = game ?: return
        if (celebrating) return
        if (transition { g.undo() }) changed() else listener?.onMessage("Nothing to undo")
    }

    fun showHint() {
        val g = game ?: return
        if (celebrating) return
        val moves = g.hints()
        hintStart = now()
        if (moves.isEmpty()) {
            hint = null
            if (g.canDeal()) { hintStock = true; listener?.onMessage("No moves left — deal from the stock") }
            else listener?.onMessage("No useful moves left. Try undo or a new game")
        } else {
            hintStock = false
            hint = moves[hintCycle % moves.size]
            hintCycle++
        }
        invalidate()
    }

    private fun changed() {
        hint = null; hintStock = false; hintCycle = 0
        invalidate()
        listener?.onGameChanged()
        if (game?.isWon == true) {
            listener?.onWon()
            celebrationPending = true
        }
    }

    private fun rejectFeedback() =
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS

    // ------------------------------------------------------------------ hint & shake state

    private var hint: Move? = null
    private var hintStock = false
    private var hintCycle = 0
    private var hintStart = 0L

    private var shakeCol = -1
    private var shakeIdx = 0
    private var shakeStart = -SHAKE_MS

    private fun startShake(col: Int, idx: Int) {
        shakeCol = col; shakeIdx = idx; shakeStart = now()
        performHapticFeedback(rejectFeedback())
        invalidate()
    }

    private fun shakeOffset(t: Long): Float {
        val p = (t - shakeStart).toFloat() / SHAKE_MS
        if (p !in 0f..1f) return 0f
        return sin(p * PI.toFloat() * 6f) * (1f - p) * cardW * 0.09f
    }

    // ------------------------------------------------------------------ win celebration

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val card: Card)

    private var celebrating = false
    private var celebrationPending = false
    private var celebrationStart = 0L
    private var lastFrame = 0L
    private var launched = 0
    private val launchQueue = ArrayList<Pair<Card, Float>>()
    private val particles = ArrayList<Particle>()
    private var trail: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private val rnd = Random(SystemClock.uptimeMillis())

    private fun startCelebration() {
        val g = game ?: return
        if (width <= 0 || height <= 0) { listener?.onCelebrationFinished(); return }
        launchQueue.clear()
        for (rank in 13 downTo 1) for (k in g.completed.indices) {
            launchQueue.add(Card(g.completed[k], rank, true) to foundationX(k))
        }
        particles.clear()
        launched = 0
        trail = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        trailCanvas = Canvas(trail!!)
        celebrating = true
        celebrationStart = now()
        lastFrame = celebrationStart
        invalidate()
    }

    private fun stopCelebration(notify: Boolean) {
        if (!celebrating) return
        celebrating = false
        particles.clear()
        trail?.recycle(); trail = null; trailCanvas = null
        invalidate()
        if (notify) listener?.onCelebrationFinished()
    }

    private fun drawCelebration(canvas: Canvas, t: Long) {
        val dt = ((t - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = t
        while (launched < launchQueue.size && t - celebrationStart >= launched * 70L) {
            val (card, x) = launchQueue[launched++]
            val dir = if (rnd.nextBoolean()) 1f else -1f
            particles.add(
                Particle(
                    x, topY,
                    dir * (0.12f + rnd.nextFloat() * 0.3f) * width,
                    -(0.1f + rnd.nextFloat() * 0.5f) * height,
                    card,
                ),
            )
        }
        val gravity = height * 2.4f
        val floor = height - cardH
        val tc = trailCanvas
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.vy += gravity * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.y > floor) { p.y = floor; p.vy = -p.vy * 0.72f }
            if (p.x < -cardW || p.x > width) { it.remove(); continue }
            if (tc != null) drawCard(tc, p.card, p.x, p.y, shadowAlpha = 0)
        }
        // Remaining kings wait on the foundations until they jump.
        for (i in launched until min(launchQueue.size, launched + (game?.completed?.size ?: 0))) {
            val (card, x) = launchQueue[i]
            drawCard(canvas, card, x, topY)
        }
        trail?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (launched >= launchQueue.size && particles.isEmpty()) stopCelebration(notify = true)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCelebration(notify = false)
    }

    companion object {
        private const val HINT_MS = 2400L
        private const val SHAKE_MS = 320L
        private val RANKS = arrayOf("", "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

        /** Suit shapes in a 24×24 box: spade, heart, club, diamond. */
        private val SUIT_PATHS: Array<Path> by lazy { arrayOf(spade(), heart(), club(), diamond()) }

        private fun heart() = Path().apply {
            moveTo(12f, 21f)
            cubicTo(5f, 15.5f, 2f, 12f, 2f, 8f)
            cubicTo(2f, 5f, 4.5f, 3f, 7.2f, 3f)
            cubicTo(9.2f, 3f, 11f, 4.3f, 12f, 6f)
            cubicTo(13f, 4.3f, 14.8f, 3f, 16.8f, 3f)
            cubicTo(19.5f, 3f, 22f, 5f, 22f, 8f)
            cubicTo(22f, 12f, 19f, 15.5f, 12f, 21f)
            close()
        }

        private fun spade(): Path {
            val body = heart()
            body.transform(Matrix().apply { postScale(0.95f, -0.92f, 12f, 12f); postTranslate(0f, -1.6f) })
            val stem = Path().apply {
                moveTo(12f, 12f)
                quadTo(12.5f, 19.5f, 15.6f, 22.5f)
                lineTo(8.4f, 22.5f)
                quadTo(11.5f, 19.5f, 12f, 12f)
                close()
            }
            body.op(stem, Path.Op.UNION)
            return body
        }

        private fun club(): Path {
            val p = Path().apply { addCircle(12f, 6.6f, 4.6f, Path.Direction.CW) }
            p.op(Path().apply { addCircle(6.6f, 13.2f, 4.6f, Path.Direction.CW) }, Path.Op.UNION)
            p.op(Path().apply { addCircle(17.4f, 13.2f, 4.6f, Path.Direction.CW) }, Path.Op.UNION)
            p.op(Path().apply { addCircle(12f, 11.6f, 2.8f, Path.Direction.CW) }, Path.Op.UNION)
            val stem = Path().apply {
                moveTo(12f, 10f)
                quadTo(12.6f, 19.5f, 15.6f, 22.5f)
                lineTo(8.4f, 22.5f)
                quadTo(11.4f, 19.5f, 12f, 10f)
                close()
            }
            p.op(stem, Path.Op.UNION)
            return p
        }

        private fun diamond() = Path().apply {
            moveTo(12f, 1.5f)
            quadTo(14.8f, 8.6f, 19.6f, 12f)
            quadTo(14.8f, 15.4f, 12f, 22.5f)
            quadTo(9.2f, 15.4f, 4.4f, 12f)
            quadTo(9.2f, 8.6f, 12f, 1.5f)
            close()
        }
    }
}
