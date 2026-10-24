package de.wtzg.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpiderGameTest {

    private fun up(rank: Int, suit: Int = 0) = Card(suit, rank, true)
    private fun down(rank: Int, suit: Int = 0) = Card(suit, rank, false)
    private fun layout(vararg cols: List<Card>) = List(SpiderGame.COLUMNS) { cols.getOrElse(it) { listOf(up(1)) } }

    @Test
    fun newGameDealsCorrectly() {
        for (suits in listOf(1, 2, 4)) {
            val g = SpiderGame.newGame(suits, Random(42))
            assertEquals(50, g.stock.size)
            assertEquals(listOf(6, 6, 6, 6, 5, 5, 5, 5, 5, 5), g.columns.map { it.size })
            g.columns.forEach { col ->
                assertTrue(col.last().faceUp)
                assertTrue(col.dropLast(1).none { it.faceUp })
            }
            val all = g.columns.flatten() + g.stock
            assertEquals(104, all.size)
            assertEquals(suits, all.map { it.suit }.distinct().size)
            all.groupBy { it.suit to it.rank }.values.forEach { assertEquals(8 / suits, it.size) }
        }
    }

    @Test
    fun onlySameSuitRunsCanBePickedUp() {
        val g = SpiderGame.fromLayout(layout(listOf(down(9), up(8), up(7), up(6, suit = 1))))
        assertTrue(g.canPickUp(0, 3))
        assertFalse(g.canPickUp(0, 2)) // 7♠ 6♥ is mixed
        assertFalse(g.canPickUp(0, 0)) // face down
    }

    @Test
    fun moveFlipsRevealedCardAndCostsAPoint() {
        val g = SpiderGame.fromLayout(layout(listOf(down(2), up(7)), listOf(up(8))))
        assertTrue(g.move(Move(0, 1, 1)))
        assertTrue(g.columns[0].last().faceUp)
        assertEquals(listOf(8, 7), g.columns[1].map { it.rank })
        assertEquals(499, g.score)
        assertFalse(g.move(Move(1, 0, 0))) // 8 can't go on a 2
    }

    @Test
    fun anyCardCanGoToEmptyColumn() {
        val cols = layout(listOf(up(5)), emptyList()).toMutableList()
        val g = SpiderGame.fromLayout(cols)
        assertTrue(g.move(Move(0, 0, 1)))
    }

    @Test
    fun completedRunIsCollected() {
        val run = (13 downTo 2).map { up(it) }
        val g = SpiderGame.fromLayout(layout(listOf(down(5)) + run, listOf(up(1))))
        assertTrue(g.move(Move(1, 0, 0)))
        assertEquals(1, g.columns[0].size)
        assertTrue(g.columns[0][0].faceUp)
        assertEquals(listOf(0), g.completed)
        assertEquals(500 - 1 + 100, g.score)
    }

    @Test
    fun cannotDealWithEmptyColumn() {
        val stock = List(10) { down(3) }
        val g = SpiderGame.fromLayout(layout(emptyList()), stock)
        assertFalse(g.canDeal())
        val full = SpiderGame.fromLayout(layout(), stock)
        assertTrue(full.deal())
        assertTrue(full.columns.all { it.size == 2 && it.last().faceUp })
        assertEquals(0, full.stock.size)
    }

    @Test
    fun undoRestoresState() {
        val g = SpiderGame.newGame(1, Random(7))
        val before = g.columns.map { it.toList() }
        assertTrue(g.deal())
        assertTrue(g.undo())
        assertEquals(before, g.columns.map { it.toList() })
        assertEquals(50, g.stock.size)
        assertFalse(g.undo())
    }

    @Test
    fun serializationRoundTrips() {
        val g = SpiderGame.newGame(4, Random(3))
        g.deal()
        val copy = SpiderGame.deserialize(g.serialize())
        assertNotNull(copy)
        assertEquals(g.serialize(), copy!!.serialize())
        assertEquals(g.columns, copy.columns)
    }

    @Test
    fun bestTargetPrefersSameSuit() {
        val g = SpiderGame.fromLayout(layout(listOf(up(5)), listOf(up(6, suit = 1)), listOf(up(6))))
        assertEquals(2, g.bestTarget(0, 0))
    }

    /** Plays many random games through the public API and checks the 104 cards are never lost. */
    @Test
    fun randomPlayKeepsInvariants() {
        val rnd = Random(1)
        repeat(200) { game ->
            val g = SpiderGame.newGame(listOf(1, 2, 4)[game % 3], Random(game))
            repeat(300) {
                val hints = g.hints()
                when {
                    hints.isNotEmpty() && rnd.nextInt(10) > 0 -> assertTrue(g.move(hints[rnd.nextInt(min(3, hints.size))]))
                    g.canDeal() -> g.deal()
                    g.canUndo && rnd.nextBoolean() -> g.undo()
                }
                val total = g.columns.sumOf { it.size } + g.stock.size + g.completed.size * 13
                assertEquals(104, total)
                g.columns.forEach { c -> if (c.isNotEmpty()) assertTrue(c.last().faceUp) }
            }
        }
    }

    @Test
    fun cardIdsAreUniqueAndSurviveMovesAndCompletion() {
        val g = SpiderGame.newGame(2, Random(5))
        fun ids() = (g.columns.flatten() + g.stock).map { it.id } + g.completedIds.flatten()
        assertEquals((0 until 104).toSet(), ids().toSet())
        g.deal()
        g.hints().firstOrNull()?.let { g.move(it) }
        assertEquals((0 until 104).toSet(), ids().toSet())

        val run = (13 downTo 2).map { up(it) }
        val h = SpiderGame.fromLayout(layout(run, listOf(up(1))))
        val runIds = h.columns[0].map { it.id } + h.columns[1].map { it.id }
        assertTrue(h.move(Move(1, 0, 0)))
        assertEquals(listOf(runIds), h.completedIds)
        assertTrue(h.undo())
        assertTrue(h.completedIds.isEmpty())
        assertEquals(runIds, h.columns[0].map { it.id } + h.columns[1].map { it.id })
    }

    @Test
    fun oldSaveFormatStillLoads() {
        // A version-1 save (no card ids) with one completed run.
        val col = (0 until 10).joinToString("|") { "${52 + 4}" }
        val v1 = "1;2;600;42;1;3,16;$col"
        val g = SpiderGame.deserialize(v1)
        assertNotNull(g)
        assertEquals(1, g!!.completedIds.size)
        val ids = (g.columns.flatten() + g.stock).map { it.id } + g.completedIds.flatten()
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(g.serialize(), SpiderGame.deserialize(g.serialize())!!.serialize())
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b
}
