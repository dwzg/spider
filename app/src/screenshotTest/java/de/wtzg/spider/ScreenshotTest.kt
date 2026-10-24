package de.wtzg.spider

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * Renders the app on the JVM and writes PNGs to app/build/screenshots/.
 * Only compiled with -Pscreenshots (see app/build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {

    private val out = File("build/screenshots").apply { mkdirs() }
    private lateinit var root: View
    private lateinit var board: SpiderView

    private fun launch() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        root = activity.findViewById(android.R.id.content)
        board = findBoard(root)!!
        idle(0)
    }

    private fun findBoard(v: View): SpiderView? {
        if (v is SpiderView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findBoard(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Advance the clock in 16 ms frames, drawing each one like a real device would. */
    private fun idle(ms: Long) {
        if (!::root.isInitialized || root.rootView.width == 0) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); return
        }
        val scratch = Canvas(Bitmap.createBitmap(root.rootView.width, root.rootView.height, Bitmap.Config.ARGB_8888))
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            board.draw(scratch)
            left -= step
        }
    }

    private fun shot(name: String) {
        val w = root.rootView.width
        val h = root.rootView.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.rootView.draw(Canvas(bmp))
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tap(col: Int, index: Int) {
        val p = board.cardTouchPoint(col, index)
        val t = SystemClock.uptimeMillis()
        board.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, p.x, p.y, 0))
        board.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, p.x, p.y, 0))
    }

    private fun up(rank: Int, suit: Int) = Card(suit, rank, true)
    private fun down() = Card(0, 1, false)

    @Test
    fun openingDeal() {
        launch()
        idle(250); shot("01_deal_early")
        idle(450); shot("02_deal_mid")
        idle(2500); shot("03_dealt")
    }

    @Test
    fun midGameAndAnimations() {
        launch()
        idle(3000)
        val g = SpiderGame.fromLayout(
            listOf(
                listOf(down(), down(), down(), up(13, 0), up(12, 0), up(11, 0), up(10, 0)),
                listOf(down(), down(), up(9, 1), up(8, 1)),
                listOf(down(), down(), down(), down(), up(5, 2)),
                listOf(down(), up(1, 3)),
                listOf(down(), down(), up(12, 1), up(11, 3), up(10, 3), up(9, 3)),
                listOf(up(7, 0), up(6, 0), up(5, 0), up(4, 0), up(3, 0)),
                listOf(down(), down(), down(), up(4, 1)),
                emptyList(),
                listOf(down(), up(6, 2)),
                listOf(down(), down(), up(2, 3)),
            ),
            stock = List(30) { down() },
            completed = listOf(2, 1),
        )
        board.setGame(g, dealAnimation = false)
        idle(50); shot("04_midgame")

        board.showHint(); idle(500); shot("05_hint")
        idle(3000)

        tap(1, 2) // 9♥ 8♥ -> onto 10♠
        idle(110); shot("06_move_in_flight")
        idle(1000); shot("07_after_move")

        tap(5, 0) // whole run from column 5 -> nowhere useful: 7♠ goes on 8♥
        idle(1200); shot("08_after_second_move")
        tap(2, 4) // 5♣ can't go anywhere except empty col → moves; tap again shakes
        idle(1200)
        board.undo(); idle(90); shot("09_undo_in_flight")
        idle(1200)
    }

    @Test
    fun winCelebration() {
        launch()
        idle(3000)
        val g = SpiderGame.fromLayout(
            listOf(
                (13 downTo 2).map { up(it, 3) },
                listOf(up(1, 3)),
            ) + List(8) { emptyList() },
            completed = listOf(0, 1, 2, 3, 0, 1, 2),
        )
        board.setGame(g, dealAnimation = false)
        idle(50)
        tap(1, 0)
        idle(450); shot("10_collect_in_flight")
        idle(2500); shot("11_celebration")
        idle(3000); shot("12_celebration_later")
    }

    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xxhdpi")
    fun landscape() {
        launch()
        idle(3000); shot("13_landscape")
    }
}
