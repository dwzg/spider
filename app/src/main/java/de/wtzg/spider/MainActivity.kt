package de.wtzg.spider

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SpiderView.Listener {

    private lateinit var board: SpiderView
    private lateinit var scoreText: TextView
    private lateinit var movesText: TextView
    private lateinit var undoButton: View
    private val prefs by lazy { getSharedPreferences("spider", Context.MODE_PRIVATE) }

    private var shownScore = 0
    private var scoreAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        board = SpiderView(this).also { it.listener = this }
        scoreText = statValue()
        movesText = statValue()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(8), dp(6))
            addView(stat(scoreText, "SCORE"))
            addView(stat(movesText, "MOVES").apply { setPadding(dp(20), 0, 0, 0) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(iconButton(R.drawable.ic_new, "New") { askNewGame() })
            undoButton = iconButton(R.drawable.ic_undo, "Undo") { board.undo() }
            addView(undoButton)
            addView(iconButton(R.drawable.ic_hint, "Hint") { board.showHint() })
        }
        val barHolder = FrameLayout(this).apply {
            setBackgroundColor(BAR_COLOR)
            addView(bar)
            // Thin highlight line on top of the bar.
            addView(View(context).apply { setBackgroundColor(0x1FFFFFFF) }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(1)))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BAR_COLOR)
            addView(board, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(barHolder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        // The felt draws behind the status bar; the board keeps its cards below it.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val l: Int; val t: Int; val r: Int; val b: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = i.left; t = i.top; r = i.right; b = i.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.let { l = it.systemWindowInsetLeft; t = it.systemWindowInsetTop; r = it.systemWindowInsetRight; b = it.systemWindowInsetBottom }
            }
            board.topInset = t
            board.setPadding(l, 0, r, 0)
            barHolder.setPadding(l, 0, r, b)
            insets
        }
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
        }
        setContentView(root)

        val saved = prefs.getString(KEY_GAME, null)?.let { SpiderGame.deserialize(it) }
        if (saved != null && !saved.isWon) {
            board.setGame(saved, dealAnimation = false)
            updateStatus(animate = false)
        } else {
            startGame(prefs.getInt(KEY_SUITS, 1))
        }
    }

    override fun onPause() {
        super.onPause()
        board.game?.let { prefs.edit().putString(KEY_GAME, it.serialize()).apply() }
    }

    private fun startGame(suits: Int) {
        prefs.edit()
            .putInt(KEY_SUITS, suits)
            .putInt("played_$suits", prefs.getInt("played_$suits", 0) + 1)
            .putBoolean(KEY_WIN_RECORDED, false)
            .apply()
        board.setGame(SpiderGame.newGame(suits), dealAnimation = true)
        updateStatus(animate = false)
    }

    private fun askNewGame() {
        val options = listOf(
            Triple(1, "Easy", "1 suit"),
            Triple(2, "Medium", "2 suits"),
            Triple(4, "Hard", "4 suits"),
        )
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("New game")
            .setView(list)
            .setNegativeButton("Cancel", null)
            .create()
        val current = board.game?.suitCount
        for ((suits, name, detail) in options) {
            val won = prefs.getInt("won_$suits", 0)
            val played = prefs.getInt("played_$suits", 0)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = selectableBackground()
                isClickable = true
                setOnClickListener { dialog.dismiss(); startGame(suits) }
                addView(TextView(context).apply {
                    text = if (suits == current) "$name  ·  current" else name
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = if (played > 0) "$detail  ·  won $won of $played" else detail
                    textSize = 14f
                    alpha = 0.7f
                })
            }
            list.addView(row)
        }
        dialog.show()
    }

    override fun onGameChanged() = updateStatus(animate = true)

    private fun updateStatus(animate: Boolean) {
        val g = board.game ?: return
        movesText.text = g.moves.toString()
        val enabled = g.canUndo
        undoButton.isEnabled = enabled
        undoButton.animate().alpha(if (enabled) 1f else 0.35f).setDuration(150).start()

        scoreAnimator?.cancel()
        if (!animate || shownScore == g.score) {
            shownScore = g.score
            scoreText.text = g.score.toString()
            return
        }
        val from = shownScore
        val to = g.score
        scoreAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = if (abs(to - from) > 10) 700 else 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shownScore = it.animatedValue as Int
                scoreText.text = shownScore.toString()
            }
            start()
        }
        if (to > from) {
            // A finished run: give the score a little pop.
            scoreText.animate().scaleX(1.25f).scaleY(1.25f).setDuration(160).withEndAction {
                scoreText.animate().scaleX(1f).scaleY(1f).setDuration(240).start()
            }.start()
        }
    }

    override fun onMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onWon() {
        val g = board.game ?: return
        if (!prefs.getBoolean(KEY_WIN_RECORDED, false)) {
            val s = g.suitCount
            prefs.edit()
                .putInt("won_$s", prefs.getInt("won_$s", 0) + 1)
                .putBoolean(KEY_WIN_RECORDED, true)
                .apply()
        }
    }

    override fun onCelebrationFinished() {
        val g = board.game ?: return
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("You won!")
            .setMessage("Score ${g.score} in ${g.moves} moves.")
            .setPositiveButton("New game") { _, _ -> askNewGame() }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------- views

    private fun statValue() = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun stat(value: TextView, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(value)
        addView(TextView(context).apply {
            text = label
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(0x99FFFFFF.toInt())
        })
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(0xE6FFFFFF.toInt())
            gravity = Gravity.CENTER
            setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
            compoundDrawablePadding = dp(2)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = selectableBackground(borderless = true)
            isClickable = true
            setOnClickListener {
                it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
                onClick()
            }
        }

    private fun selectableBackground(borderless: Boolean = false) = TypedValue().let { tv ->
        val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
        theme.resolveAttribute(attr, tv, true)
        getDrawable(tv.resourceId)
    } ?: GradientDrawable()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun abs(v: Int) = if (v < 0) -v else v

    companion object {
        private const val KEY_GAME = "game"
        private const val KEY_SUITS = "suits"
        private const val KEY_WIN_RECORDED = "win_recorded"
        private const val BAR_COLOR = 0xFF06361C.toInt()
    }
}
