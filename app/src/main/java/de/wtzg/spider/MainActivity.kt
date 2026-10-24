package de.wtzg.spider

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), SpiderView.Listener {

    private lateinit var board: SpiderView
    private lateinit var status: TextView
    private lateinit var undoButton: Button
    private val prefs by lazy { getSharedPreferences("spider", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        board = SpiderView(this).also { it.listener = this }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(12), 0, dp(4), 0)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF074D28.toInt())
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("New") { askNewGame() })
            undoButton = button("Undo") { board.undo() }
            addView(undoButton)
            addView(button("Hint") { board.showHint() })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF074D28.toInt())
            addView(board, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)

        val saved = prefs.getString(KEY_GAME, null)?.let { SpiderGame.deserialize(it) }
        if (saved != null && !saved.isWon) {
            board.game = saved
            onGameChanged()
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
        board.game = SpiderGame.newGame(suits)
        onGameChanged()
    }

    private fun askNewGame() {
        val options = intArrayOf(1, 2, 4)
        val labels = options.map { s ->
            val name = when (s) { 1 -> "1 suit · Easy"; 2 -> "2 suits · Medium"; else -> "4 suits · Hard" }
            "$name   (won ${prefs.getInt("won_$s", 0)} of ${prefs.getInt("played_$s", 0)})"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("New game")
            .setItems(labels) { _, which -> startGame(options[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onGameChanged() {
        val g = board.game ?: return
        status.text = "Score ${g.score}  ·  Moves ${g.moves}"
        undoButton.isEnabled = g.canUndo
        undoButton.alpha = if (g.canUndo) 1f else 0.4f
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
        AlertDialog.Builder(this)
            .setTitle("You won! 🎉")
            .setMessage("Score ${g.score} in ${g.moves} moves.")
            .setPositiveButton("New game") { _, _ -> askNewGame() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun button(text: String, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            setTextColor(Color.WHITE)
            isAllCaps = false
            textSize = 15f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_GAME = "game"
        private const val KEY_SUITS = "suits"
        private const val KEY_WIN_RECORDED = "win_recorded"
    }
}
