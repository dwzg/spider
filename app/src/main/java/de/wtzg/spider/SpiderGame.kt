package de.wtzg.spider

import kotlin.math.abs
import kotlin.random.Random

/**
 * suit: 0 = spades, 1 = hearts, 2 = clubs, 3 = diamonds. rank: 1 (ace) .. 13 (king).
 * [id] is unique per card in a game (0..103) so the UI can follow a card as it moves.
 */
data class Card(val suit: Int, val rank: Int, val faceUp: Boolean = false, val id: Int = 0) {
    val isRed: Boolean get() = suit == 1 || suit == 3

    fun encode(): Int = id * 104 + (suit * 13 + rank - 1) + if (faceUp) 52 else 0

    companion object {
        fun decode(n: Int): Card {
            val c = n % 104
            return Card((c % 52) / 13, c % 13 + 1, c >= 52, n / 104)
        }
    }
}

/** Move the cards of column [from] starting at [index] onto column [to]. */
data class Move(val from: Int, val index: Int, val to: Int)

class SpiderGame private constructor(
    val suitCount: Int,
    val columns: List<MutableList<Card>>,
    val stock: MutableList<Card>,
    /** Suit of every completed King-to-Ace run, in completion order. */
    val completed: MutableList<Int>,
    /** Card ids of every completed run (King first), parallel to [completed]. */
    val completedIds: MutableList<List<Int>>,
    score: Int,
    moves: Int,
) {
    var score = score
        private set
    var moves = moves
        private set

    private class Snapshot(
        val columns: List<List<Card>>,
        val stock: List<Card>,
        val completed: List<Int>,
        val completedIds: List<List<Int>>,
        val score: Int,
        val moves: Int,
    )

    private val history = ArrayList<Snapshot>()

    val isWon: Boolean get() = completed.size == DECKS
    val dealsLeft: Int get() = stock.size / COLUMNS
    val canUndo: Boolean get() = history.isNotEmpty()

    /** True when the cards from [index] to the end of [col] form a movable same-suit run. */
    fun canPickUp(col: Int, index: Int): Boolean {
        val c = columns.getOrNull(col) ?: return false
        if (index !in c.indices) return false
        for (i in index until c.size) {
            if (!c[i].faceUp) return false
            if (i > index) {
                val prev = c[i - 1]
                if (prev.suit != c[i].suit || prev.rank != c[i].rank + 1) return false
            }
        }
        return true
    }

    fun canDrop(card: Card, to: Int): Boolean {
        val top = columns[to].lastOrNull() ?: return true
        return top.faceUp && top.rank == card.rank + 1
    }

    fun isLegal(m: Move): Boolean =
        m.from != m.to && m.to in 0 until COLUMNS &&
            canPickUp(m.from, m.index) && canDrop(columns[m.from][m.index], m.to)

    fun move(m: Move): Boolean {
        if (!isLegal(m)) return false
        save()
        val src = columns[m.from]
        val moving = src.subList(m.index, src.size).toList()
        repeat(moving.size) { src.removeAt(src.lastIndex) }
        columns[m.to].addAll(moving)
        flipTop(m.from)
        moves++
        score--
        collectRun(m.to)
        return true
    }

    /** Classic rule: dealing is only allowed when no column is empty. */
    fun canDeal(): Boolean = stock.isNotEmpty() && columns.none { it.isEmpty() }

    fun deal(): Boolean {
        if (!canDeal()) return false
        save()
        for (col in columns) col.add(stock.removeAt(stock.lastIndex).copy(faceUp = true))
        moves++
        score--
        for (i in columns.indices) collectRun(i)
        return true
    }

    fun undo(): Boolean {
        val s = history.removeLastOrNull() ?: return false
        columns.forEachIndexed { i, c -> c.clear(); c.addAll(s.columns[i]) }
        stock.clear(); stock.addAll(s.stock)
        completed.clear(); completed.addAll(s.completed)
        completedIds.clear(); completedIds.addAll(s.completedIds)
        score = s.score - 1 // undo still costs a point, like the classic game
        moves = s.moves + 1
        return true
    }

    /**
     * Where a tap on (col, index) should send the cards, or null if no move helps.
     *
     * Ranking: a same-suit stack (the one that ends up with the longest same-suit run wins),
     * then another suit (the stack whose own run gets broken least), then an empty column.
     * Remaining ties go to the nearest column. Moves that only make things worse are skipped:
     * a whole column into an empty one, or lifting cards off a same-suit parent onto a
     * different suit or an empty column.
     */
    fun bestTarget(col: Int, index: Int): Int? {
        if (!canPickUp(col, index)) return null
        val src = columns[col]
        val card = src[index]
        val moving = src.size - index
        val parent = src.getOrNull(index - 1)
        val onSameSuitParent = parent != null && parent.faceUp &&
            parent.suit == card.suit && parent.rank == card.rank + 1
        var best: Int? = null
        var bestScore = Int.MIN_VALUE
        for (to in 0 until COLUMNS) {
            if (to == col || !canDrop(card, to)) continue
            val top = columns[to].lastOrNull()
            var score = when {
                top == null -> if (index == 0 || onSameSuitParent) continue else 1_000
                top.suit == card.suit -> 100_000 + 100 * (suitRunLength(to) + moving)
                onSameSuitParent -> continue
                else -> 10_000 - 100 * suitRunLength(to)
            }
            score -= abs(to - col)
            if (score > bestScore) { bestScore = score; best = to }
        }
        return best
    }

    /** Length of the same-suit descending run at the bottom of a column. */
    private fun suitRunLength(col: Int): Int {
        val c = columns[col]
        if (c.isEmpty()) return 0
        var n = 1
        for (i in c.lastIndex downTo 1) {
            val a = c[i - 1]; val b = c[i]
            if (!a.faceUp || a.suit != b.suit || a.rank != b.rank + 1) break
            n++
        }
        return n
    }

    /** Useful moves, best first. Skips moves that only shuffle cards sideways. */
    fun hints(): List<Move> {
        val scored = ArrayList<Pair<Move, Int>>()
        for (from in 0 until COLUMNS) {
            val c = columns[from]
            for (index in c.indices) {
                if (!canPickUp(from, index)) continue
                val card = c[index]
                val parent = c.getOrNull(index - 1)
                val parentFits = parent != null && parent.faceUp && parent.rank == card.rank + 1
                val parentSame = parentFits && parent!!.suit == card.suit
                val reveals = parent != null && !parent.faceUp
                if (parentSame) continue
                for (to in 0 until COLUMNS) {
                    if (to == from || !canDrop(card, to)) continue
                    val top = columns[to].lastOrNull()
                    if (top == null && (index == 0 || parentFits)) continue
                    val destSame = top != null && top.suit == card.suit
                    if (parentFits && !destSame) continue
                    var s = 0
                    if (destSame) s += 4
                    if (reveals) s += 3
                    if (index == 0 && top != null) s += 3 // empties a column
                    if (top != null) s += 1
                    s += c.size - index // longer runs first
                    scored.add(Move(from, index, to) to s)
                }
            }
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    fun serialize(): String = listOf(
        "2",
        suitCount.toString(),
        score.toString(),
        moves.toString(),
        completed.joinToString(","),
        stock.joinToString(",") { it.encode().toString() },
        columns.joinToString("|") { col -> col.joinToString(",") { it.encode().toString() } },
        completedIds.joinToString("|") { it.joinToString(",") },
    ).joinToString(";")

    private fun save() {
        history.add(
            Snapshot(columns.map { it.toList() }, stock.toList(), completed.toList(), completedIds.toList(), score, moves),
        )
        if (history.size > MAX_UNDO) history.removeAt(0)
    }

    private fun flipTop(col: Int) {
        val c = columns[col]
        val top = c.lastOrNull() ?: return
        if (!top.faceUp) c[c.lastIndex] = top.copy(faceUp = true)
    }

    private fun collectRun(col: Int) {
        val c = columns[col]
        if (c.size < 13) return
        val start = c.size - 13
        val suit = c[start].suit
        for (i in 0 until 13) {
            val card = c[start + i]
            if (!card.faceUp || card.suit != suit || card.rank != 13 - i) return
        }
        completedIds.add(c.subList(start, c.size).map { it.id })
        repeat(13) { c.removeAt(c.lastIndex) }
        completed.add(suit)
        score += 100
        flipTop(col)
    }

    companion object {
        const val COLUMNS = 10
        const val DECKS = 8
        private const val MAX_UNDO = 1000

        fun newGame(suitCount: Int, random: Random = Random.Default): SpiderGame {
            val suits = when (suitCount) {
                1 -> listOf(0)
                2 -> listOf(0, 1)
                else -> listOf(0, 1, 2, 3)
            }
            val deck = (0 until 8 * 13).map { Card(suits[(it / 13) % suits.size], it % 13 + 1, id = it) }
                .shuffled(random)
            val columns = List(COLUMNS) { ArrayList<Card>() }
            for (i in 0 until 54) columns[i % COLUMNS].add(deck[i])
            for (c in columns) c[c.lastIndex] = c.last().copy(faceUp = true)
            return SpiderGame(suits.size, columns, deck.drop(54).toMutableList(), ArrayList(), ArrayList(), 500, 0)
        }

        fun deserialize(s: String): SpiderGame? = try {
            val p = s.split(";")
            val v1 = p[0] == "1"
            require((v1 && p.size == 7) || (p[0] == "2" && p.size == 8))
            fun ints(t: String) = if (t.isEmpty()) emptyList() else t.split(",").map { it.toInt() }
            val cols = p[6].split("|").map { t -> ints(t).map(Card::decode).toMutableList() }
            require(cols.size == COLUMNS)
            val stock = ints(p[5]).map(Card::decode).toMutableList()
            val completed = ints(p[4]).toMutableList()
            val completedIds: MutableList<List<Int>> =
                if (v1) completed.indices.map { emptyList<Int>() }.toMutableList()
                else p[7].split("|").filter { it.isNotEmpty() }.map { ints(it) }.toMutableList()
            if (v1) {
                // Old saves had no card ids: hand out fresh ones.
                var next = 0
                for (c in cols) for (i in c.indices) c[i] = c[i].copy(id = next++)
                for (i in stock.indices) stock[i] = stock[i].copy(id = next++)
                for (k in completedIds.indices) completedIds[k] = List(13) { next++ }
            }
            require(completedIds.size == completed.size)
            SpiderGame(p[1].toInt(), cols, stock, completed, completedIds, p[2].toInt(), p[3].toInt())
        } catch (e: Exception) {
            null
        }

        /** Test helper: build a game from an explicit layout. */
        internal fun fromLayout(
            columns: List<List<Card>>,
            stock: List<Card> = emptyList(),
            completed: List<Int> = emptyList(),
        ): SpiderGame {
            var next = 0
            return SpiderGame(
                1,
                columns.map { col -> col.map { it.copy(id = next++) }.toMutableList() },
                stock.map { it.copy(id = next++) }.toMutableList(),
                completed.toMutableList(),
                completed.map { List(13) { next++ } }.toMutableList(),
                500, 0,
            )
        }
    }
}
