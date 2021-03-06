@file:Suppress("NAME_SHADOWING", "UnnecessaryVariable")

import BitField.Companion.bitField
import Direction.*
import Items.NO_ITEM
import Items.indexesToNames
import OnePush.Companion.onePush
import RepetitionType.*
import Tweaks.scoreCollisionOnlyForPreviousPushes
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.text.DecimalFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureNanoTime

val startTime = System.currentTimeMillis()
fun log(s: Any?) {
    System.err.println("\n#[${System.currentTimeMillis() - startTime}] $s\n")
}

var super_cached = 0
var cached = 0
var non_cached = 0
var quests_not_match = 0

val setupMonitoring = run {
    log("java started as: ${ManagementFactory.getRuntimeMXBean().inputArguments}")

    val garbageCollectorMXBeans: Collection<GarbageCollectorMXBean> =
        ManagementFactory.getGarbageCollectorMXBeans()


    garbageCollectorMXBeans.forEach { bean ->

        var lastCount = bean.collectionCount
        var lastTime = bean.collectionTime
        val listener = NotificationListener { _, _ ->
            val newCount = bean.collectionCount
            val newTime = bean.collectionTime
            log("GC: ${bean.name} collectionsCount=${newCount - lastCount} collectionsTime=${newTime - lastTime}ms totalCollectionTime=$newTime")
            lastCount = newCount
            lastTime = newTime
        }
        (bean as NotificationEmitter).addNotificationListener(
            listener,
            null,
            "haha"
        )
    }
    log("setup monitoring done")
}

// util

val winP = Array(75) { Array(13) { DoubleArray(13) } }
val loseP = Array(75) { Array(13) { DoubleArray(13) } }
val drawP = Array(75) { Array(13) { DoubleArray(13) } }
val estimate = Array(75) { Array(13) { DoubleArray(13) } }

fun main() {
    performGame()
}

enum class RepetitionType {
    SAME_MOVE,

    NOT_PREVIOUS_MOVE,

    RANDOM_MOVE,

    UNKNOWN
}

val drawRepetitions = Array(11) { UNKNOWN }
val nonDrawRepetitions = Array(150) { UNKNOWN }

val drawRepetitionsStr: String
    get() {
        return drawRepetitions.toList().subList(1, max(2, drawRepetitions.indexOfLast { it != UNKNOWN } + 1))
            .joinToString()
    }
val nonDrawRepetitionsStr: String
    get() {
        return nonDrawRepetitions.toList().subList(1, max(2, nonDrawRepetitions.indexOfLast { it != UNKNOWN } + 1))
            .joinToString()
    }

private fun initProbabilities(p: DoubleArray) {
    var p00 = p[0]
    var p01 = p[1]
    var p02 = p[2]
    var p10 = p01
    var p11 = p[3]
    var p12 = p[4]
    var p20 = p02
    var p21 = p12
    var p22 = p[5]
    val sum = p00 + p01 + p02 + p10 + p11 + p12 + p20 + p21 + p22
    p00 /= sum
    p01 /= sum
    p02 /= sum
    p10 /= sum
    p11 /= sum
    p12 /= sum
    p20 /= sum
    p21 /= sum
    p22 /= sum
    val oe = doubleArrayOf(p00, p01, p02, p10, p11, p12, p20, p21, p22)
    for (o in (0 until 13)) {
        for (e in (0 until 13)) {
            if (o == e) {
                drawP[0][o][e] = 1.0
                estimate[0][o][e] = 0.5
            } else if (e > o) {
                winP[0][o][e] = 1.0
                estimate[0][o][e] = 1.0
            } else {
                loseP[0][o][e] = 1.0
            }
        }
    }
    for (turn in (1 until 75)) {
        for (o in (1 until 13)) {
            loseP[turn][o][0] = 1.0
        }
        for (e in (1 until 13)) {
            winP[turn][0][e] = 1.0
            estimate[turn][0][e] = 1.0
        }
        drawP[turn][0][0] = 1.0
        estimate[turn][0][0] = 0.5
    }

    for (turn in (1 until 75)) {
        for (o in (1 until 13)) {
            for (e in (1 until 13)) {
                for (oi in (0 until 3)) {
                    for (ei in (0 until 3)) {
                        val op = Math.max(o - oi, 0)
                        val ep = Math.max(e - ei, 0)
                        drawP[turn][o][e] += drawP[turn - 1][op][ep] * oe[oi + ei * 3]
                        winP[turn][o][e] += winP[turn - 1][op][ep] * oe[oi + ei * 3]
                        loseP[turn][o][e] += loseP[turn - 1][op][ep] * oe[oi + ei * 3]
                    }
                }
                val sum = winP[turn][o][e] + drawP[turn][o][e] + loseP[turn][o][e]
                winP[turn][o][e] /= sum
                drawP[turn][o][e] /= sum
                loseP[turn][o][e] /= sum
                estimate[turn][o][e] = winP[turn][o][e] + drawP[turn][o][e] / 2
            }
        }
    }
}

val twoDigitsAfterDotFormat = DecimalFormat().apply {
    maximumFractionDigits = 2
}
var collisionPredicted = false

val probablyLogCompilation: () -> Unit = run {
    var lastCompilationTime = 0L
    val compilationBean = ManagementFactory.getCompilationMXBean()

    log("compilation log installed")
    return@run {
        val compilationTime = compilationBean.totalCompilationTime
        if (compilationTime > lastCompilationTime) {
            log("Compilation: diff: ${compilationTime - lastCompilationTime} total: $compilationTime")
            lastCompilationTime = compilationTime
        }
    }
}

val rand = Random(777)

val allPushScores = DoubleArray(49 * 49 * 28 * 28)
val ourPointsSumScore = DoubleArray(49)
val scoreForPoints = DoubleArray(49 * 49)

val moveScores = DoubleArray(49)
val movePushScores = Array(49) { DoubleArray(28) }


var lastBoard: GameBoard? = null
var lastBoardAndElves: BoardAndElves? = null
var lastPush: OnePush? = null
var numberOfDraws = 0
var numberOfDrawsWithoutMoves = 0
var pushesRemain = 74

data class BoardAndElves(val gameBoard: GameBoard, val ourElf: Point, val enemyElf: Point)

data class Fingerprint(val vq: Byte, val hq: Byte, val v: Byte, val h: Byte)

private val defaultP = doubleArrayOf(0.6383, 0.1429, 0.0163, 0.1429, 0.0338, 0.0044, 0.0163, 0.0044, 0.0007)

fun performGame() {
    Warmup.warmupOnce()
    Pushes.installRealPushes()

    Quests.clear()

    try {
//        val input = Scanner(System.`in`)
        val input = Scanner(TeeInputStream(System.`in`, System.err))

//        val input = Scanner(
//            StringReader(
//                """""".trimIndent()
//            )
//        )

        val allBoards = mutableMapOf<BoardAndElves, MutableList<Pushes>>()

        repeat(150) { step ->
            pushesRemain = (150 - step) / 2 - 1

            log("step $step")

            val (turnType, gameBoard, we, enemy) = readInput(input)
            val start = System.nanoTime()
            if (step == 0) {
                val fields: MutableList<BitField> = Point.points
                    .map { p -> gameBoard.bitBoard[p] }
                    .toMutableList()
                fields.add(gameBoard.bitBoard.ourField())
                fields.add(gameBoard.bitBoard.enemyField())
                val vi = fields.filter { it.item != 0 }
                    .map { Integer.bitCount(it.tile.and(0b0101)) }.sum()
                val hi = fields.filter { it.item != 0 }
                    .map { Integer.bitCount(it.tile.and(0b1010)) }.sum()
                val v = fields.map { Integer.bitCount(it.tile.and(0b0101)) }.sum()
                val h = fields.map { Integer.bitCount(it.tile.and(0b1010)) }.sum()

                val key = if (vi < hi || (vi == hi && v < h)) {
                    Fingerprint(vi.toByte(), hi.toByte(), v.toByte(), h.toByte())
                } else {
                    Fingerprint(hi.toByte(), vi.toByte(), h.toByte(), v.toByte())
                }
                if (fingerprints.containsKey(key)) {
                    initProbabilities(fingerprints.get(key)!!)
                } else {
                    log("!unknown $key")
                    initProbabilities(defaultP)
                }
                fingerprints.clear()
            }

            if (turnType == 0) {
                val duration = measureNanoTime {
                    val prevMovesAtThisPosition = allBoards[BoardAndElves(gameBoard, we.point, enemy.point)]

                    log("consecutiveDraws=$numberOfDraws drawsWOMoves=$numberOfDrawsWithoutMoves duplicate=${prevMovesAtThisPosition != null}")

                    val bestMove = findBestPush(
                        we,
                        enemy,
                        gameBoard,
                        prevMovesAtThisPosition,
                        numberOfDraws
                    )

                    lastBoard = gameBoard
                    lastBoardAndElves = BoardAndElves(gameBoard, we.point, enemy.point)
                    lastPush = bestMove
                }

                log("$step pushDuration: ${TimeUnit.NANOSECONDS.toMillis(duration)}")
                while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(20)) {
                    log("time to sleep")
                    Thread.sleep(10)
                }

                val bestMove = lastPush!!
                println("PUSH ${bestMove.rowColumn} ${bestMove.direction}")
            } else {
                var output: String? = null
                val duration = measureNanoTime {
                    processPreviousPush(gameBoard, we, enemy, allBoards)
                    val bestPath = findBestMove(
                        gameBoard,
                        we,
                        step,
                        enemy
                    )

                    output = if (bestPath != null) {
                        val directions = mutableListOf<Direction>()
                        var pathElem: PathElem = bestPath
                        while (pathElem.prev != null) {
                            val direction = pathElem.direction!!
                            directions.add(0, direction)
                            pathElem = pathElem.prev!!
                        }
                        if (directions.isEmpty()) {
                            "PASS"
                        } else {
                            "MOVE " + directions.joinToString(separator = " ")
                        }
                    } else {
                        "PASS"
                    }
                }
                log("$step moveDuration: ${TimeUnit.NANOSECONDS.toMillis(duration)}")

                if (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(30)) {
                    log("time to sleep")
                    Thread.sleep(10)
                }
                println(output!!)
            }
        }
    } catch (t: Throwable) {
        t.printStackTrace()
        throw t
    }
}

object Quests {
    val globalQuestsInGameOrder = mutableListOf<Int>()
    val ourQuestsInGameOrder = mutableListOf<Int>()
    val enemyQuestsInGameOrder = mutableListOf<Int>()
    private var ourIdx = 0
    private var enemyIdx = 0

    fun clear() {
        globalQuestsInGameOrder.clear()
        ourQuestsInGameOrder.clear()
        enemyQuestsInGameOrder.clear()
        ourIdx = 0
        enemyIdx = 0
    }

    fun updateQuests(ourQuests: List<String>, enemyQuests: List<String>) {
        val updateGlobal = globalQuestsInGameOrder.size < 12
        ourQuests.forEachIndexed { index, it ->
            val item = Items.index(it)
            if (updateGlobal) {
                if (!globalQuestsInGameOrder.contains(item)) {
                    globalQuestsInGameOrder.add(item)
                    enemyQuestsInGameOrder.add(item)
                    ourQuestsInGameOrder.add(item)
                }
            }

            while (ourQuestsInGameOrder[ourIdx + index] != item) {
                if (index != 0) {
                    val q = ourQuestsInGameOrder[ourIdx + index]
                    ourQuestsInGameOrder.removeAt(ourIdx + index)
                    ourQuestsInGameOrder.add(ourIdx, q)
                }
                ourIdx++
            }
        }

        enemyQuests.forEachIndexed { index, it ->
            val item = Items.index(it)
            if (updateGlobal) {
                if (!globalQuestsInGameOrder.contains(item)) {
                    globalQuestsInGameOrder.add(item)
                    enemyQuestsInGameOrder.add(item)
                    ourQuestsInGameOrder.add(item)
                }
            }

            while (enemyQuestsInGameOrder[enemyIdx + index] != item) {
                if (index != 0) {
                    val q = enemyQuestsInGameOrder[enemyIdx + index]
                    enemyQuestsInGameOrder.removeAt(enemyIdx + index)
                    enemyQuestsInGameOrder.add(enemyIdx, q)
                }
                enemyIdx++
            }
        }

        if (globalQuestsInGameOrder.size == 11) {
            for (item in Items.items) {
                val idx = Items.index(item)
                if (!globalQuestsInGameOrder.contains(idx)) {
                    globalQuestsInGameOrder.add(idx)
                    enemyQuestsInGameOrder.add(idx)
                    ourQuestsInGameOrder.add(idx)
                }
            }
        }
    }

    fun size() = globalQuestsInGameOrder.size
    fun questByIdx(idx: Int): Int {
        return if (idx < globalQuestsInGameOrder.size) {
            globalQuestsInGameOrder[idx]
        } else {
            0
        }
    }
}

val ourPlayersCache = Array(49 * Pushes.ALL_PUSHES_SIZE) { Player(0, 0, 0, 0, 0, 0, 0) }
val enemyPlayersCache = Array(49 * Pushes.ALL_PUSHES_SIZE) { Player(0, 0, 0, 0, 0, 0, 0) }
val gameBoardCache = Array(Pushes.ALL_PUSHES_SIZE) { GameBoard(BitBoard(LongArray(7), LongArray(2))) }

fun findBestMove(
    gameBoard: GameBoard,
    we: Player,
    step: Int,
    enemy: Player
): PathElem? {
    log("findBestMove")
    val startTime = System.nanoTime()
    val scoreTimeLimit = if (noTimeLimit) Long.MAX_VALUE else TimeUnit.MILLISECONDS.toNanos(if (step == 0) 500 else 21)
    val pivotTimeLimit = if (noTimeLimit) Long.MAX_VALUE else TimeUnit.MILLISECONDS.toNanos(if (step == 0) 500 else 50)

    val ourPaths = gameBoard.findPaths(we, we.currentQuests)
    val ourItemsTaken = ourPaths.maxWith(compareBy { Integer.bitCount(it.itemsTakenSet) })!!.itemsTakenSet
    val ourItemsTakenSize = Integer.bitCount(ourItemsTaken)

    val ourBestPaths = ourPaths.filter { Integer.bitCount(it.itemsTakenSet) == ourItemsTakenSize }
    val ourEnds = ourBestPaths.map { it.point }.distinct()
    val OUR_ENDS_SIZE = ourEnds.size
    if (OUR_ENDS_SIZE == 1) {
        return ourPaths.find { Integer.bitCount(it.itemsTakenSet) == ourItemsTakenSize }!!
    }

    val enemyPaths = gameBoard.findPaths(enemy, enemy.currentQuests)
    val enemyItemsTaken = enemyPaths.maxWith(compareBy { Integer.bitCount(it.itemsTakenSet) })!!.itemsTakenSet
    val enemyItemsTakenSize = Integer.bitCount(enemyItemsTaken)

    val enemyEnds = enemyPaths.filter { Integer.bitCount(it.itemsTakenSet) == enemyItemsTakenSize }
        .map { it.point }.distinct()

    val ENEMY_ENDS_SIZE = enemyEnds.size
    log("move space is $OUR_ENDS_SIZE*$ENEMY_ENDS_SIZE = ${OUR_ENDS_SIZE * ENEMY_ENDS_SIZE}")

    fun idx(ourPoint: Point, enemyPoint: Point, ourPush: OnePush, enemyPush: OnePush) =
        ((ourPoint.idx * ENEMY_ENDS_SIZE + enemyPoint.idx) * 28 + ourPush.idx) * 28 + enemyPush.idx

    var enemyEndsProcessed = 0
    we.takeQuests(ourItemsTaken) { we ->
        enemy.takeQuests(enemyItemsTaken) { enemy ->
            run {
                val ourQuests = we.currentQuests.indexesToNames()
                val enemyQuests = enemy.currentQuests.indexesToNames()
                log("Our next quests: $ourQuests;  enemy next quests: $enemyQuests}")
            }

            ourPointsSumScore.fill(0.0)
            ourEnds.forEach { moveScores[it.idx] = 0.0 }
            for (p in ourEnds) {
                movePushScores[p.idx].fill(0.0)
            }
            allPushScores.fill(0.0)

            var count = 0

            for (pushes in Pushes.allPushes) {
                for (pointIdx in (0 until OUR_ENDS_SIZE)) {
                    val point = ourEnds[pointIdx]
                    val player = ourPlayersCache[pushes.idx * OUR_ENDS_SIZE + pointIdx]
                    player.copy_inPlace(we, point = point)
                    player.push_inPlace(pushes, gameBoard)
                }
                gameBoardCache[pushes.idx] = gameBoard.push(pushes) //todo: copy in place?
            }

            for (enemyPointIdx in (0 until ENEMY_ENDS_SIZE)) {
                run {
                    for (pushes in Pushes.allPushes) {
                        val point = enemyEnds[enemyPointIdx]
                        val player = enemyPlayersCache[pushes.idx * ENEMY_ENDS_SIZE + enemyPointIdx]
                        player.copy_inPlace(enemy, point = point)
                        player.push_inPlace(pushes, gameBoard)
                    }
                }
                val enemyPoint = enemyEnds[enemyPointIdx]
                var aborted = false
                for (pushes in Pushes.allPushes) {
                    count++
                    val newBoard = gameBoardCache[pushes.idx]
                    enemyPlayersCache[pushes.idx * ENEMY_ENDS_SIZE + enemyPointIdx].let { enemy ->
                        for (ourPointIdx in (0 until OUR_ENDS_SIZE)) {
                            val ourPoint = ourEnds[ourPointIdx]
                            ourPlayersCache[pushes.idx * OUR_ENDS_SIZE + ourPointIdx].let { we ->
                                val score = PushAndMove.calcScore(pushes, newBoard, we, enemy)
                                ourPointsSumScore[ourPoint.idx] += score
                                moveScores[ourPoint.idx] += score
                                movePushScores[ourPoint.idx][pushes.ourPush.idx] += score
                                allPushScores[idx(ourPoint, enemyPoint, pushes.ourPush, pushes.enemyPush)] = score
                            }
                        }
                    }
                    if (System.nanoTime() - startTime > scoreTimeLimit) {
                        log("stop computePushes, computed $count pushes")
                        aborted = true
                        break
                    }
                }
                if (aborted) {
                    break
                } else {
                    enemyEndsProcessed++
                }
            }
        }
    }

    var bestByPivot: PathElem? = null

    if (enemyEndsProcessed > 0) {
        if (enemyEndsProcessed != enemyEnds.size) {
            log("process scores $enemyEndsProcessed from ${enemyEnds.size}")
        }
        run {
            val threshold = 0.0000001
            fun r(value: Double): Double =
                if (value > -threshold && value < threshold) 0.0 else value

            val processedEnemy = enemyEnds.subList(0, enemyEndsProcessed)
            var pivotedEnemyEnds = 0
            for (enemyPoint in processedEnemy) {
                var aborted = false
                for (ourPoint in ourEnds) {
                    for (pushes in Pushes.allPushes) {
                        val score = r(allPushScores[idx(ourPoint, enemyPoint, pushes.ourPush, pushes.enemyPush)])
                        a[pushes.enemyPush.idx][pushes.ourPush.idx] = score
                    }

                    if (printScores) log("will solve ourPoint=$ourPoint enemyPoint=$enemyPoint")
                    probablyPrintScores(OnePush.allPushes, OnePush.allPushes)
                    scoreForPoints[ourPoint.idx * 49 + enemyPoint.idx] = solvePivot(28, 28).score
                    if (System.nanoTime() - startTime > pivotTimeLimit) {
                        log("stop computePivots (bestMove)")
                        aborted = true
                        break
                    }
                }
                if (aborted) {
                    break
                } else {
                    pivotedEnemyEnds++
                }
            }
            if (pivotedEnemyEnds > 0) {
                if (pivotedEnemyEnds != processedEnemy.size) {
                    log("process pivots $pivotedEnemyEnds from ${processedEnemy.size}")
                }
                val pivotedEnemy = processedEnemy.subList(0, pivotedEnemyEnds)
                for ((ourPointIdx, ourPoint) in ourEnds.withIndex()) {
                    for ((enemyPointIdx, enemyPoint) in pivotedEnemy.withIndex()) {
                        a[enemyPointIdx][ourPointIdx] = scoreForPoints[ourPoint.idx * 49 + enemyPoint.idx]
                    }
                }
                probablyPrintScores(ourEnds, pivotedEnemy, actionToStringSize = 5)
                val (score, ourStrategy, enemyStrategy) = solvePivot(pivotedEnemy.size, ourEnds.size)
                log("score: $score")
                log("Our strategy: ${
                    ourEnds
                        .mapIndexed { index, point -> point to ourStrategy[index] }
                        .sortedByDescending { it.second }
                        .joinToString { "${it.first}=${twoDigitsAfterDotFormat.format(it.second)}" }
                }")
                log(
                    "Enemy strategy: ${
                        pivotedEnemy
                            .mapIndexed { index, point -> point to enemyStrategy[index] }
                            .sortedByDescending { it.second }
                            .joinToString { "${it.first}=${twoDigitsAfterDotFormat.format(it.second)}" }
                    }")

                bestByPivot = ourEnds
                    .mapIndexed { index, point -> point to ourStrategy[index] }
                    .maxBy { it.second }!!
                    .first
                    .let { point -> ourBestPaths.first { it.point == point } }
            }
        }
    }

    val maxScoreByPoint = movePushScores.map { it.max() }

    val scoreComparator = compareBy<PathElem> { pathElem ->
        moveScores[pathElem.point.idx]
    }.thenComparing { pathElem ->
        max(2 * abs(pathElem.point.x - 3) + 1, 2 * abs(pathElem.point.y - 3))
    }.thenComparing { pathElem ->
        if (gameBoard.bitBoard.ourField().containsQuestItem(we.playerId, we.currentQuests)) {
            val (x, y) = pathElem.point
            if ((x == 0 || x == 6) && (y == 0 || y == 6)) {
                1
            } else {
                0
            }
        } else {
            0
        }
    }.thenComparing { pathElem ->
        Integer.bitCount(gameBoard.bitBoard[pathElem.point].tile)
    }
    val pathsComparator = compareBy<PathElem> { pathElem ->
        Integer.bitCount(pathElem.itemsTakenSet)
    }.thenComparing { pathElem ->
        maxScoreByPoint[pathElem.point.idx]!!
    }.thenComparing(scoreComparator)

    val bestPath = ourPaths.maxWith(pathsComparator)
    val maxByAverageScore = ourPaths.maxWith(compareBy<PathElem> { pathElem ->
        Integer.bitCount(pathElem.itemsTakenSet)
    }.thenComparing(scoreComparator))
    log("MoveCandidate by average score ${maxByAverageScore?.point}, current best ${bestPath?.point}, pivot ${bestByPivot?.point}")

    probablyLogCompilation()
    return bestByPivot ?: bestPath
}


private fun processPreviousPush(
    gameBoard: GameBoard,
    we: Player,
    enemy: Player,
    allBoards: MutableMap<BoardAndElves, MutableList<Pushes>>
) {
    val lastPush = lastPush!!
    val lastBoard = lastBoard!!
    val lastBoardAndElves = lastBoardAndElves!!
    val wasDrawAtPrevMove = lastBoard == gameBoard
    if (collisionPredicted) {
        if (wasDrawAtPrevMove)
            log("prophetic collision prediction")
        else {
            log("unfulfilled collision prediction")
        }
    }
    val wasMove = we.point != lastBoardAndElves.ourElf || enemy.point != lastBoardAndElves.enemyElf
    val wasDrawSequence = numberOfDrawsWithoutMoves > 0
    val wasDrawSequenceLength = numberOfDrawsWithoutMoves
    if (wasDrawAtPrevMove) {
        numberOfDraws++
        if (wasMove) {
            numberOfDrawsWithoutMoves = 0
        } else {
            numberOfDrawsWithoutMoves++
        }
    } else {
        numberOfDraws = 0
        numberOfDrawsWithoutMoves = 0
    }
    val prevMoves = allBoards[lastBoardAndElves]
    if (!wasDrawAtPrevMove) {
        val enemyLastPush = tryFindEnemyPush(lastBoard, gameBoard, lastPush)

        if (enemyLastPush != null) {
            if (wasDrawSequence && prevMoves != null) {
                val sameMoveAsPrev = prevMoves.last().ourPush.collision(enemyLastPush)
                drawRepetitions[wasDrawSequenceLength] = if (sameMoveAsPrev) {
                    when (drawRepetitions[wasDrawSequenceLength]) {
                        UNKNOWN -> SAME_MOVE
                        SAME_MOVE -> SAME_MOVE
                        NOT_PREVIOUS_MOVE -> RANDOM_MOVE
                        RANDOM_MOVE -> RANDOM_MOVE
                    }
                } else {
                    when (drawRepetitions[wasDrawSequenceLength]) {
                        UNKNOWN -> NOT_PREVIOUS_MOVE
                        SAME_MOVE -> RANDOM_MOVE
                        NOT_PREVIOUS_MOVE -> NOT_PREVIOUS_MOVE
                        RANDOM_MOVE -> RANDOM_MOVE
                    }
                }
            } else {
                val prevMoves = prevMoves
                if (prevMoves != null) {
                    val sameMoveAsPrev = prevMoves.last().enemyPush == enemyLastPush
                    val seqLength = prevMoves.filterNot { it.collision }.size
                    nonDrawRepetitions[seqLength] = if (sameMoveAsPrev) {
                        when (nonDrawRepetitions[seqLength]) {
                            UNKNOWN -> SAME_MOVE
                            SAME_MOVE -> SAME_MOVE
                            NOT_PREVIOUS_MOVE -> RANDOM_MOVE
                            RANDOM_MOVE -> RANDOM_MOVE
                        }
                    } else {
                        when (nonDrawRepetitions[seqLength]) {
                            UNKNOWN -> NOT_PREVIOUS_MOVE
                            SAME_MOVE -> RANDOM_MOVE
                            NOT_PREVIOUS_MOVE -> NOT_PREVIOUS_MOVE
                            RANDOM_MOVE -> RANDOM_MOVE
                        }
                    }
                }

                allBoards.computeIfAbsent(lastBoardAndElves) { _ -> mutableListOf() }
                    .add(Pushes(lastPush, enemyLastPush))
            }
        }
    } else { // was draw
        if (wasDrawSequence && prevMoves != null) {
            val sameMoveAsPrev = prevMoves.last().ourPush.collision(lastPush)
            drawRepetitions[wasDrawSequenceLength] = if (sameMoveAsPrev) {
                when (drawRepetitions[wasDrawSequenceLength]) {
                    UNKNOWN -> SAME_MOVE
                    SAME_MOVE -> SAME_MOVE
                    NOT_PREVIOUS_MOVE -> RANDOM_MOVE
                    RANDOM_MOVE -> RANDOM_MOVE
                }
            } else {
                when (drawRepetitions[wasDrawSequenceLength]) {
                    UNKNOWN -> NOT_PREVIOUS_MOVE
                    SAME_MOVE -> RANDOM_MOVE
                    NOT_PREVIOUS_MOVE -> NOT_PREVIOUS_MOVE
                    RANDOM_MOVE -> RANDOM_MOVE
                }
            }
        } else {// first draw
            val prevMoves = prevMoves
            if (prevMoves != null) {
                val sameMoveAsPrev = prevMoves.last().enemyPush.collision(lastPush)
                val seqLength = prevMoves.size
                nonDrawRepetitions[seqLength] = if (sameMoveAsPrev) {
                    when (nonDrawRepetitions[seqLength]) {
                        UNKNOWN -> SAME_MOVE
                        SAME_MOVE -> SAME_MOVE
                        NOT_PREVIOUS_MOVE -> RANDOM_MOVE
                        RANDOM_MOVE -> RANDOM_MOVE
                    }
                } else {
                    when (nonDrawRepetitions[seqLength]) {
                        UNKNOWN -> NOT_PREVIOUS_MOVE
                        SAME_MOVE -> RANDOM_MOVE
                        NOT_PREVIOUS_MOVE -> NOT_PREVIOUS_MOVE
                        RANDOM_MOVE -> RANDOM_MOVE
                    }
                }
            }
        }

        unifyRepetitions(drawRepetitions)
        unifyRepetitions(nonDrawRepetitions)

        val score = we.push(enemy.playerId, lastPush, gameBoard) { we ->
            enemy.push(enemy.playerId, lastPush, gameBoard) { enemy ->
                gameBoard.push(lastPush, enemy).score(
                    ourPlayer = we,
                    enemyPlayer = enemy,
                    collision = false
                )
            }
        }
        val oppositeScore = we.push(enemy.playerId, lastPush.opposite, gameBoard) { we ->
            enemy.push(enemy.playerId, lastPush.opposite, gameBoard) { enemy ->
                gameBoard.push(lastPush.opposite, enemy).score(
                    ourPlayer = we,
                    enemyPlayer = enemy,
                    collision = false
                )
            }
        }
        if (score < oppositeScore) {
            log("! $score < $oppositeScore deduct enemy $lastPush")
            allBoards.computeIfAbsent(lastBoardAndElves) { _ -> mutableListOf() }
                .add(Pushes(lastPush, lastPush))
        } else {
            log("! $score >= $oppositeScore deduct enemy ${lastPush.opposite}")
            allBoards.computeIfAbsent(lastBoardAndElves) { _ -> mutableListOf() }
                .add(Pushes(lastPush, lastPush.opposite))
        }
    }
}

private fun unifyRepetitions(repetitions: Array<RepetitionType>) {
    var foundRandom = false
    for (i in repetitions.indices) {
        if (repetitions[i] == RANDOM_MOVE) {
            foundRandom = true
        }
        if (repetitions[i] != UNKNOWN && foundRandom) {
            repetitions[i] = RANDOM_MOVE
        }
    }
}

fun tryFindEnemyPush(fromBoard: GameBoard, toBoard: GameBoard, ourPush: OnePush): OnePush? {
    for (enemyRowColumn in (0..6)) {
        for (enemyDirection in Direction.allDirections) {
            val draw =
                enemyRowColumn == ourPush.rowColumn && (ourPush.direction == enemyDirection || ourPush.direction == enemyDirection.opposite)

            if (draw) {
                continue
            }
            val enemyPush = onePush(enemyDirection, enemyRowColumn)
            val newBoard = fromBoard.push(Pushes(ourPush, enemyPush))
            if (newBoard == toBoard) {
                return enemyPush
            }
        }
    }
    log("can not find enemy push for our $ourPush")
    return null // for example if item was taken immediately after push we cannot find enemy move
}

fun readInput(input: Scanner): InputConditions {
    val turnType = input.nextInt() // 0 - push, 1 - move
    val board = BoardDto(input)

    val we = Player(0, input)
    val ourTile = Tile.read(input)

    val enemy = Player(1, input)
    val enemyTile = Tile.read(input)

    val items = (0 until input.nextInt()).map { ItemDto(input) }
    val quests = (0 until input.nextInt()).map { QuestDto(input) }

    val ourAlreadyTakenQuests = items.foldRight(initial = 0b1111_1111_1111_0) { item, result ->
        if (item.itemPlayerId == we.playerId) {
            result.flip(Items.index(item.itemName))
        } else {
            result
        }
    }
    val enemyAlreadyTakenQuests = items.foldRight(initial = 0b1111_1111_1111_0) { item, result ->
        if (item.itemPlayerId == enemy.playerId) {
            result.flip(Items.index(item.itemName))
        } else {
            result
        }
    }
    return we.copy(alreadyTakenQuests = ourAlreadyTakenQuests) { we ->
        enemy.copy(alreadyTakenQuests = enemyAlreadyTakenQuests) { enemy ->


            val ourField = Field(ourTile,
                item = items.singleOrNull { it.isOnOurHand }
                    ?.toItem()
                    ?: NO_ITEM
            )
            val enemyField = Field(enemyTile,
                item = items.singleOrNull { it.isOnEnemyHand }
                    ?.toItem()
                    ?: 0
            )

            val ourQuests = quests.filter { it.questPlayerId == 0 }.map { it.questItemName }
            val enemyQuests = quests.filter { it.questPlayerId == 1 }.map { it.questItemName }
            Quests.updateQuests(ourQuests, enemyQuests)

            we.copy(lastQuestIdx = Quests.globalQuestsInGameOrder.indexOf(Items.index(ourQuests.last()))) { we ->
                enemy.copy(lastQuestIdx = Quests.globalQuestsInGameOrder.indexOf(Items.index(enemyQuests.last()))) { enemy ->

                    val boardArray = Array(7 * 7) { idx ->
                        val x = idx % 7
                        val y = idx / 7
                        Field(
                            board.board[y][x],
                            items.singleOrNull { it.itemX == x && it.itemY == y }?.toItem() ?: NO_ITEM
                        )
                    }
                    val gameBoard = GameBoard(BitBoard.newInstance(boardArray, ourField, enemyField))

                    var ourQuestsSet = 0
                    ourQuests.forEach {
                        ourQuestsSet = ourQuestsSet.set(Items.index(it))
                    }

                    var enemyQuestsSet = 0
                    enemyQuests.forEach {
                        enemyQuestsSet = enemyQuestsSet.set(Items.index(it))
                    }

                    we.copy(currentQuests = ourQuestsSet) { we ->
                        enemy.copy(currentQuests = enemyQuestsSet) { enemy ->

                            val ttn = if (turnType == 0) "PUSH" else "MOVE"
                            val ourBoardField = gameBoard[we.point]

                            val ourImmediateQuest =
                                if (ourBoardField.containsQuestItem(we.playerId, ourQuestsSet)) {
                                    val quest = abs(ourBoardField.item)
                                    log(
                                        "we standing at ${Items.name(quest)} at $ttn turn. change ourQuestsSet from " +
                                                "${ourQuestsSet.indexesToNames()} to ${we.currentQuests.indexesToNames()}"
                                    )
                                    0.set(quest)
                                } else {
                                    0
                                }
                            we.takeQuests(ourImmediateQuest) { we ->
                                val enemyBoardField = gameBoard[enemy.point]
                                val enemyImmediateQuest =
                                    if (enemyBoardField.containsQuestItem(enemy.playerId, enemyQuestsSet)) {
                                        val quest = abs(enemyBoardField.item)
                                        log(
                                            "enemy standing at ${Items.name(quest)} at $ttn turn. change enemyQuestsSet from " +
                                                    "${enemyQuestsSet.indexesToNames()} to ${enemy.currentQuests.indexesToNames()}"
                                        )
                                        0.set(quest)
                                    } else {
                                        0
                                    }
                                enemy.takeQuests(enemyImmediateQuest) { enemy ->
                                    log("board score = ${gameBoard.score(we, enemy, false)}")
                                    InputConditions(turnType, gameBoard, we.escapeCopy(), enemy.escapeCopy())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class InputConditions(
    val turnType: Int,
    val gameBoard: GameBoard,
    var we: Player,
    var enemy: Player
)

data class OnePush private constructor(val direction: Direction, val rowColumn: Int) {
    val idx = direction.ordinal * 7 + rowColumn
    lateinit var opposite: OnePush
        private set

    companion object {
        val allPushes = Direction.allDirections.flatMap { dir -> (0..6).map { OnePush(dir, it) } }

        fun byIdx(idx: Int) = allPushes[idx]

        fun onePush(direction: Direction, rowColumn: Int) = byIdx(direction.ordinal * 7 + rowColumn)

        init {
            allPushes.forEach { first ->
                allPushes.forEach { second ->
                    if (first != second && first.collision(second)) {
                        first.opposite = second
                        second.opposite = first
                    }
                }
            }
        }
    }

    override fun toString(): String {
        return "${direction.name.first()}$rowColumn"
    }

    fun collision(other: OnePush): Boolean {
        return this.rowColumn == other.rowColumn && (this.direction.isVertical == other.direction.isVertical)
    }
}

data class Pushes(val ourPush: OnePush, val enemyPush: OnePush) {
    val idx = ourPush.idx * 28 + enemyPush.idx
    val firstPush = if (ourPush.direction.isVertical) {
        enemyPush
    } else {
        ourPush
    }
    val secondPush = if (ourPush.direction.isVertical) {
        ourPush
    } else {
        enemyPush
    }
    val firstPushIsEnemy = ourPush.direction.isVertical
    val firstPlayerId = if (firstPushIsEnemy) 1 else 0
    val secondPlayerId = if (firstPushIsEnemy) 0 else 1

    val collision = ourPush.collision(enemyPush)

    companion object {
        val ALL_PUSHES_SIZE = 28*28
        val realAllPushes = Direction.allDirections.flatMap { ourDirection ->
            (0..6).flatMap { ourRowColumn ->
                Direction.allDirections.flatMap { enemyDirection ->
                    (0..6).map { enemyRowColumn ->
                        Pushes(
                            onePush(ourDirection, ourRowColumn),
                            onePush(enemyDirection, enemyRowColumn)
                        )
                    }
                }
            }
        }.toTypedArray()

        var allPushes: Array<Pushes> = Direction.allDirections.flatMap { ourDirection ->
            (0..0).flatMap { ourRowColumn ->
                Direction.allDirections.flatMap { enemyDirection ->
                    (0..0).map { enemyRowColumn ->
                        Pushes(
                            onePush(ourDirection, ourRowColumn),
                            onePush(enemyDirection, enemyRowColumn)
                        )
                    }
                }
            }
        }.toTypedArray()

        fun installRealPushes() {
            allPushes = realAllPushes
        }
    }

}

class PushAndMove(
    val pushes: Pushes,
    val board: GameBoard,
    ourPlayer: Player,
    enemyPlayer: Player,
    useCollisionAtScore: Boolean = false
) {

    val score: Double = calcScore(pushes, board, ourPlayer, enemyPlayer, useCollisionAtScore)

    companion object {
        fun calcScore(
            pushes: Pushes,
            board: GameBoard,
            ourPlayer: Player,
            enemyPlayer: Player,
            useCollisionAtScore: Boolean = false
        ): Double {
            val collision = useCollisionAtScore && pushes.collision
            val ourDomain = board.findDomain(ourPlayer.point, ourPlayer.currentQuests, enemyPlayer.currentQuests)
            val enemyDomain =
                board.findDomain(enemyPlayer.point, enemyPlayer.currentQuests, enemyPlayer.currentQuests)

            return board.score(
                ourPlayer,
                enemyPlayer,
                collision,
                ourDomain = ourDomain,
                enemyDomain = enemyDomain
            )
        }
    }
}

private fun findBestPush(
    we: Player,
    enemy: Player,
    gameBoard: GameBoard,
    prevPushesAtThisPosition: List<Pushes>? = null,
    numberOfDraws: Int = 0
): OnePush {
    probablyLogCompilation()
    val previousPushes = if (prevPushesAtThisPosition != null && scoreCollisionOnlyForPreviousPushes) {
        prevPushesAtThisPosition
    } else {
        emptyList()
    }
    val pushes = computePushes(
        gameBoard,
        we,
        emptyList(),
        enemy,
        previousPushes
    )
    val filteredPushes = filterOutPushes(pushes, prevPushesAtThisPosition, numberOfDraws, we, enemy)
    val result = selectPivotSolver(filteredPushes, numberOfDraws)
    probablyLogCompilation()
    return result
}


fun computeEstimate(
    ourItemRemain: Int,
    enemyItemRemain: Int,
    pushesRemain: Int,
    secondaryScore: Double
): Double {
    if (pushesRemain < 0) {
        return 0.0
    }
    val gameEstimate = estimate[pushesRemain][ourItemRemain][enemyItemRemain]
    return if (pushesRemain == 0) {
        gameEstimate
    } else if (secondaryScore > 0) {
        var delta = estimate[pushesRemain][ourItemRemain - 1][enemyItemRemain] - gameEstimate
        if (delta < 0) {
            log("!!!Negative delta=$delta for positive secondary: [$pushesRemain][$ourItemRemain][$enemyItemRemain] ")
            delta = 0.0
        }
        gameEstimate + delta * 0.5 * secondaryScore
    } else if (secondaryScore < 0) {
        var delta = gameEstimate - estimate[pushesRemain][ourItemRemain][enemyItemRemain - 1]
        if (delta < 0) {
            log("!!!Negative delta=$delta for negative secondary: [$pushesRemain][$ourItemRemain][$enemyItemRemain] ")
            delta = 0.0
        }
        gameEstimate + delta * 0.5 * secondaryScore
    } else {
        gameEstimate
    }
}

val a = Array(49) { DoubleArray(49) { 0.0 } } // interior[column][row]
val stringBuilder = StringBuilder(10_000)

private val printScores = java.lang.Boolean.getBoolean("printScores")
private val noTimeLimit = java.lang.Boolean.getBoolean("noTimeLimit")

//pivot method from https://www.math.ucla.edu/~tom/Game_Theory/mat.pdf
private fun selectPivotSolver(
    pushes: List<PushAndMove>,
    numberOfDraws: Int
): OnePush {

    val threshold = 0.0000001
    fun r(value: Double): Double =
        if (value > -threshold && value < threshold) 0.0 else value

    val ourPushes = pushes.groupBy { it.pushes.ourPush }.keys.toList()
    val OUR_SIZE = ourPushes.size
    val enemyPushes = pushes.groupBy { it.pushes.enemyPush }.keys.toList()
    val ENEMY_SIZE = enemyPushes.size

    var haveAnyChances = false
    for (push in pushes) {
        val score = r(push.score)
        if (score > 0) {
            haveAnyChances = true
        }
        a[enemyPushes.indexOf(push.pushes.enemyPush)][ourPushes.indexOf(push.pushes.ourPush)] = score
    }
    if (!haveAnyChances) {
        log("there are no chances to win ;(")
        return ourPushes[0]
    }

    probablyPrintScores(ourPushes, enemyPushes)

    val result = solvePivot(ENEMY_SIZE, OUR_SIZE)

    run {

        val (score, ourStrategy, enemyStrategy) = result

        val selection = rand.nextDouble()

        log("score: $score")

        log(
            "OurStrategy: ${
                ourStrategy.mapIndexed { idx, score -> ourPushes[idx] to score }
                    .sortedByDescending { it.second }
                    .joinToString { "${it.first}=${twoDigitsAfterDotFormat.format(it.second)}" }
            }"
        )
        log(
            "EnemyStrategy: ${
                enemyStrategy.mapIndexed { idx, score -> enemyPushes[idx] to score }
                    .sortedByDescending { it.second }
                    .joinToString { "${it.first}=${twoDigitsAfterDotFormat.format(it.second)}" }
            }"
        )

        log("selection=$selection ourSum=${ourStrategy.sum()} enemySum=${enemyStrategy.sum()}")

        run {
            var threshold = -1.0
            val best = ourStrategy
                .mapIndexed { idx, probability -> ourPushes[idx] to probability }
                .sortedByDescending { it.second }
                .takeWhile {
                    if (threshold < 0) {
                        threshold = it.second * Tweaks.nearStrategiesThreshold
                        true
                    } else {
                        it.second > threshold
                    }
                }
            val norm = best.sumByDouble { it.second }
            var currentSum = 0.0
            if (best.size > 1) {
                log("${best.size} near strategies")
            }
            if (numberOfDraws == 0) {
                val enemyPush = enemyPushes[enemyStrategy.indices.maxBy { enemyStrategy[it] }!!]
                collisionPredicted = enemyPush.collision(best[0].first)
            } else {
                collisionPredicted = false
            }
            for (idx in best.indices) {
                currentSum += best[idx].second
                if (currentSum >= selection * norm) {
                    if (idx > 0) {
                        log("select not the first strategy")
                    }
                    return best[idx].first
                }
            }
        }
    }

    throw IllegalStateException("aaaaa")
}

private fun probablyPrintScores(
    ourActions: List<*>,
    enemyActions: List<*>,
    actionToStringSize: Int = 2
) {
    if (printScores) {
        val OUR_SIZE = ourActions.size
        val ENEMY_SIZE = enemyActions.size

        stringBuilder.clear()

        stringBuilder.append("\n#our\\enemy | ")
        enemyActions.joinTo(stringBuilder, " | ")
        stringBuilder.append("\n")
        for (j in (0 until OUR_SIZE)) {
            stringBuilder.append("#")
            stringBuilder.append(ourActions[j].toString().padStart(9))
            stringBuilder.append(" | ")
            for (i in (0 until ENEMY_SIZE)) {
                stringBuilder.append((100 * a[i][j]).toInt().toString().padStart(actionToStringSize))
                stringBuilder.append(" | ")
            }
            stringBuilder.append("\n")
        }
        log(stringBuilder.toString())
    }
}

private fun solvePivot(ENEMY_SIZE: Int, OUR_SIZE: Int): PivotSolverResult {
    var threshold = 0.0000001
    fun r(value: Double): Double =
        if (value > -threshold && value < threshold) 0.0 else value

    val hLabel = IntArray(ENEMY_SIZE) { idx -> -idx - 1 } // y_i are represented by negative ints
    val vLabel = IntArray(OUR_SIZE) { idx -> idx + 1 } // x_i are represented by  positives ints

    val bottom = DoubleArray(ENEMY_SIZE) { idx -> -1.0 }
    val right = DoubleArray(OUR_SIZE) { idx -> 1.0 }

    var corner = 0.0
    var pivotCount = 0
    val duration = measureNanoTime {
        while (bottom.any { it < 0 } /*step #6*/) {
            var p = -1
            var q = -1

            run {
                // step #3
                for (i in (0 until ENEMY_SIZE)) {
                    if (bottom[i] < 0) {
                        var min_3c = Double.MAX_VALUE
                        for (j in (0 until OUR_SIZE)) {
                            if (a[i][j] > 0) {
                                val val_3c = r(right[j] / a[i][j])
                                if (val_3c < min_3c) {
                                    min_3c = val_3c
                                    p = i
                                    q = j
                                }
                            }
                        }
                        if (p >= 0) {
                            break
                        }
                    }
                }

            }

            if (p < 0) {
                log("!!!Can not find pivot!!!")
                break
            }

            run {
                // step #4
                val pivot = a[p][q]
                pivotCount++

                corner = r(corner - bottom[p] * right[q] / pivot)

                for (i in (0 until ENEMY_SIZE)) {
                    if (i != p) {
                        bottom[i] = r(bottom[i] - bottom[p] * a[i][q] / pivot)
                    }
                }
                bottom[p] = r(-bottom[p] / pivot)

                for (j in (0 until OUR_SIZE)) {
                    if (j != q) {
                        right[j] = r(right[j] - a[p][j] * right[q] / pivot)
                    }
                }
                right[q] = r(right[q] / pivot)

                for (i in (0 until ENEMY_SIZE)) {
                    for (j in (0 until OUR_SIZE)) {
                        if (i != p && j != q) {
                            a[i][j] = r(a[i][j] - a[p][j] * a[i][q] / pivot)
                        }
                    }
                }
                for (i in (0 until ENEMY_SIZE)) {
                    for (j in (0 until OUR_SIZE)) {
                        if (i != p && j == q) {
                            a[i][j] = r(a[i][j] / pivot)
                        } else if (i == p && j != q) {
                            a[i][j] = r(-a[i][j] / pivot)
                        } else if (i == p && j == q) {
                            a[i][j] = r(1 / a[i][j])
                        }
                    }
                }
                if (pivotCount % 128 == 0) {
                    log("#$pivotCount pivot was chosen, so increase threshold")
                    threshold *= 10
                }
            }

            run {
                // step #5
                val tmp = hLabel[p]
                hLabel[p] = vLabel[q]
                vLabel[q] = tmp
            }
        }
    }

    //step 7
    val resultScore = 1 / corner

    val ourStrategy = DoubleArray(OUR_SIZE) { 0.0 }
    val enemyStrategy = DoubleArray(ENEMY_SIZE) { 0.0 }

    for (i in (0 until ENEMY_SIZE)) {
        if (hLabel[i] > 0) {
            val idx = hLabel[i] - 1
            ourStrategy[idx] = bottom[i] / corner
        }
    }
    for (j in (0 until OUR_SIZE)) {
        if (vLabel[j] < 0) {
            val idx = -vLabel[j] - 1
            enemyStrategy[idx] = right[j] / corner
        }
    }

    val result = PivotSolverResult(resultScore, ourStrategy, enemyStrategy)
    return result
}

data class PivotSolverResult(val score: Double, val ourStrategy: DoubleArray, val enemyStrategy: DoubleArray)

private fun filterOutPushes(
    pushes: List<PushAndMove>,
    prevPushesAtThisPosition: List<Pushes>?,
    numberOfDraws: Int,
    we: Player,
    enemy: Player
): List<PushAndMove> {
    log("filter pushes: prev pushes at this position: $prevPushesAtThisPosition")
    log("filter pushes: enemy draw repetitions: $drawRepetitionsStr")
    log("filter pushes: enemy nonDraw repetitions: $nonDrawRepetitionsStr")

    val prevEnemyPushes = prevPushesAtThisPosition?.flatMap {
        listOf(it.enemyPush)
    }
    val prevOurPushes = prevPushesAtThisPosition?.map { it.ourPush }

    val pushes = if (prevEnemyPushes == null || prevEnemyPushes.isEmpty()) {
        pushes
    } else {
        prevOurPushes!!
        val isDraw = numberOfDraws != 0
        val weLoseOrEarlyDraw = we.numPlayerCards > enemy.numPlayerCards ||
                (we.numPlayerCards == enemy.numPlayerCards &&
                        pushesRemain > Tweaks.earlyDrawPushesRemain &&
                        numberOfDraws > Tweaks.earlyDrawNumberOfDraws)
        var firstRandomTheSame = false
        val enemyType = if (isDraw) {
            var type: RepetitionType
            if (drawRepetitions[numberOfDraws] == UNKNOWN) {
                type = drawRepetitions[numberOfDraws - 1]
                log("I guess that enemy draw type is $type")
            } else {
                type = drawRepetitions[numberOfDraws]
                log("enemy draw type $type")
            }
            if (weLoseOrEarlyDraw && type == RANDOM_MOVE && numberOfDraws > 2) {
                val lastThreeIsRandom = drawRepetitions[numberOfDraws - 1] == RANDOM_MOVE &&
                        drawRepetitions[numberOfDraws - 2] == RANDOM_MOVE &&
                        (drawRepetitions[numberOfDraws] == RANDOM_MOVE ||
                                drawRepetitions[numberOfDraws - 3] == RANDOM_MOVE)
                if (lastThreeIsRandom) {
                    log("enemy is random but we are losing and have $numberOfDraws draws, so let's assume SAME_MOVE type")
                    firstRandomTheSame = (drawRepetitions[numberOfDraws] == RANDOM_MOVE &&
                            drawRepetitions[numberOfDraws - 3] != RANDOM_MOVE) ||
                            (numberOfDraws > 3 &&
                                    drawRepetitions[numberOfDraws] == UNKNOWN &&
                                    drawRepetitions[numberOfDraws - 4] != RANDOM_MOVE)
                    type = SAME_MOVE
                }
            }
            type
        } else {
            val numberOfRepeats = prevPushesAtThisPosition.filterNot { it.collision }.size
            if (nonDrawRepetitions[numberOfRepeats] == UNKNOWN && numberOfRepeats > 0) {
                val type = nonDrawRepetitions[numberOfRepeats - 1]
                log("I guess that enemy cycle type is $type")
                type
            } else {
                val type = nonDrawRepetitions[numberOfRepeats]
                log("enemy cycle type $type")
                type
            }
        }
        val excludeOur = weLoseOrEarlyDraw
                && (numberOfDraws > 2 || numberOfDraws == 0)
                && (enemyType == SAME_MOVE && !firstRandomTheSame)
        if (excludeOur) {
            log("filter out our pushes: $prevOurPushes")
        }

        when (enemyType) {
            NOT_PREVIOUS_MOVE -> pushes.filterNot {
                val lastEnemyPush = prevEnemyPushes.last()
                it.pushes.enemyPush == lastEnemyPush
                // || (isDraw && it.pushes.enemyPush == lastEnemyPush.opposite)
            }
            SAME_MOVE -> pushes.filter {
                val lastEnemyPush = prevEnemyPushes.last()
                val lastOurPush = prevOurPushes.last()
                if (it.pushes.enemyPush == lastEnemyPush) {
                    if (isDraw) {
                        if (it.pushes.ourPush == lastOurPush.opposite) {
                            false
                        } else if (excludeOur && it.pushes.ourPush == lastOurPush) {
                            false
                        } else {
                            true
                        }
                    } else if (excludeOur && it.pushes.ourPush == lastOurPush) {
                        false
                    } else {
                        true
                    }
                } else {
                    false
                }
            }
            else -> pushes
        }
    }
    return pushes
}

fun computePushes(
    gameBoard: GameBoard,
    we: Player,
    forbiddenOurPushes: List<OnePush> = emptyList(),
    enemy: Player,
    previousPushesAtPosition: List<Pushes> = emptyList()
): List<PushAndMove> {
    val result = mutableListOf<PushAndMove>()
    for (pushes in Pushes.allPushes) {
        if (forbiddenOurPushes.contains(pushes.ourPush)) {
            continue
        }
        we.push(pushes, gameBoard) { ourPlayer ->
            enemy.push(pushes, gameBoard) { enemyPlayer ->
                val newBoard = gameBoard.push(pushes)
                val useCollisionAtScore = previousPushesAtPosition.none { it.ourPush.collision(pushes.ourPush) }
                val pushAndMove = PushAndMove(
                    pushes = pushes,
                    board = newBoard,
                    ourPlayer = ourPlayer,
                    enemyPlayer = enemyPlayer,
                    useCollisionAtScore = useCollisionAtScore
                )
                result.add(pushAndMove)
            }
        }
    }

    return result
}

object PushSelectors {

    fun itemOnHandScore(player: Player, domain: DomainInfo, handField: BitField): Int {
        return if (domain.hasAccessToBorder && handField.ourQuestItem(player.playerId, player.currentQuests)) {
            1
        } else {
            0
        }
    }
}

class BoardDto(val board: List<List<Tile>>) {

    constructor(scanner: Scanner) : this(
        (0 until 7).map {
            (0 until 7).map {
                Tile.read(scanner)
            }
        }
    )
}

data class Field(
    val tile: Tile,
    val item: Int
)

data class BitField private constructor(val bits: Long) {
    companion object {
        val TILE_MASK: Long = 0b000001111
        val ITEM_MASK: Long = 0b111110000
        val cache = Array(size = (TILE_MASK or ITEM_MASK).toInt()) {
            BitField(it.toLong())
        }

        fun connected(field1: Long, direction: Direction, field2: Long): Boolean {
            return field1.and(direction.mask) != 0L && field2.and(direction.opposite.mask) != 0L
        }

        fun bitField(bits: Long): BitField = cache[bits.toInt()]
    }

    fun containsQuestItem(playerId: Int, questsSet: Int): Boolean {
        return if (questsSet[item.absoluteValue]) {
            if (playerId == 0 && item > 0) {
                true
            } else playerId == 1 && item < 0
        } else {
            false
        }
    }

    val item = (bits.shr(4) - 12).toInt()

    val tile = bits.and(TILE_MASK).toInt()

    fun ourQuestItem(playerId: Int, quests: Int): Boolean {
        val item = this.item
        return if (item == 0) {
            return false
        } else if (playerId == 0 && item > 0) {
            quests[item]
        } else if (playerId == 1 && item < 0) {
            quests[-item]
        } else {
            false
        }
    }
}

private operator fun Int.get(index: Int): Boolean {
    return this and (1.shl(index)) != 0
}

fun Int.set(index: Int): Int {
    return this or (1.shl(index))
}

inline fun Int.bitCount() = Integer.bitCount(this)

fun Int.flip(index: Int): Int {
    return this xor (1.shl(index))
}

private operator fun Long.get(index: Int): Boolean {
    return this and (1L.shl(index)) != 0L
}

private fun Long.set(index: Int): Long {
    return this or (1L.shl(index))
}

data class DomainInfo(
    val inputOurQuests: Int,
    val inputEnemyQuests: Int,
    val ourQuestBits: Int,
    val enemyQuestBits: Int,
    val ourItemsBits: Int,
    val enemyItemsBits: Int,
    val domainBits: Long,
    val twoPathsTileCount: Int,
    val threePathsTileCount: Int,
    val fourPathsTileCount: Int,
    val maxX: Int,
    val minX: Int,
    val maxY: Int,
    val minY: Int
) {

    val size: Int = java.lang.Long.bitCount(domainBits)
    val hasAccessToBorder = domainBits.and(BORDER) != 0L
    val tilePathsCount = 2 * twoPathsTileCount + 3 * threePathsTileCount + 4 * fourPathsTileCount
    val getOurQuestsCount: Int = Integer.bitCount(ourQuestBits)
    val getEnemyQuestsCount: Int =  Integer.bitCount(enemyQuestBits)

    companion object {
        val UP_BORDER: Long =
            0b0000000000000000000000000000000000000000001111111
        val LEFT_BORDER: Long =
            0b0000001000000100000010000001000000100000010000001
        val RIGHT_BORDER: Long =
            0b1000000100000010000001000000100000010000001000000
        val DOWN_BORDER: Long =
            0b1111111000000000000000000000000000000000000000000
        val HORIZONTAL_BORDER = UP_BORDER.or(DOWN_BORDER)
        val VERTICAL_BORDER = RIGHT_BORDER.or(LEFT_BORDER)
        val BORDER = VERTICAL_BORDER.or(HORIZONTAL_BORDER)
    }

}

data class PathElem(
    val point: Point,
    val itemsTakenSet: Int,
    var prev: PathElem?,
    var direction: Direction?
)

class Domains {
    private val domains = ArrayList<DomainInfo>(21)

    fun get(point: Point, ourQuestsSet: Int, enemyQuestsSet: Int): DomainInfo? {

        var otherDI: DomainInfo? = null
        for (i in 0 until domains.size) {
            val info = domains[i]
            if (!info.domainBits[point.idx]) {
                continue
            }
            if (info.inputOurQuests == ourQuestsSet && info.inputEnemyQuests == enemyQuestsSet) {
                return info
            } else {
                otherDI = info
                quests_not_match++
            }
        }
        if (otherDI != null) {
            val newDomainInfo = otherDI.copy(
                inputOurQuests = ourQuestsSet,
                inputEnemyQuests = enemyQuestsSet,
                ourQuestBits = ourQuestsSet and otherDI.ourItemsBits,
                enemyQuestBits = enemyQuestsSet and otherDI.enemyItemsBits
            )

            domains.add(newDomainInfo)

            return newDomainInfo
        }
        return null
    }

    fun set(domain: DomainInfo) {
        domains.add(domain)
    }

    fun count(): Int {
        return domains.size
    }
}

data class BitBoard(val rows: LongArray, val hands: LongArray) {
    companion object {
        val FIELD_MASK: Long = 0b111111111
        val MASK: LongArray = (0..6).map { FIELD_MASK.shl((6 - it) * 9) }.toLongArray()

        fun newInstance(board: Array<Field>, ourField: Field, enemyField: Field): BitBoard {
            val rows = board.toList().chunked(7)
                .map {
                    var result: Long = 0
                    for (i in (0 until 7)) {
                        result = result.shl(9).or(fromField(it[i]))
                    }
                    result
                }.toLongArray()
            val hands = longArrayOf(fromField(ourField), fromField(enemyField))
            return BitBoard(rows, hands)
        }

        fun fromField(field: Field): Long {
            return (field.item + 12L).shl(4).or(field.tile.mask)
        }

        fun pushRight(idx: Int, rows: LongArray, hands: LongArray, playerId: Int) {
            val row = rows[idx]
            val hand = hands[playerId]
            val mask = MASK[6]
            val newHand = row.and(mask)
            val newRow = row.shr(9).or(hand.shl(54))
            hands[playerId] = newHand
            rows[idx] = newRow
        }

        fun pushLeft(idx: Int, rows: LongArray, hands: LongArray, playerId: Int) {
            val row = rows[idx]
            val hand = hands[playerId]
            val mask = MASK[0]
            val pushed = row.and(mask)
            //xor clear left bits before shift
            val newRow = row.xor(pushed).shl(9).or(hand)
            hands[playerId] = pushed.shr(54)
            rows[idx] = newRow
        }

        fun pushDown(idx: Int, rows: LongArray, hands: LongArray, playerId: Int) {
            val mask = MASK[idx]
            val shift = (6 - idx) * 9
            var pushed = hands[playerId].shl(shift)
            for (i in (0 until 7)) {
                val row = rows[i]
                val newPushed = row.and(mask)
                val newRow = row.xor(newPushed).or(pushed)
                rows[i] = newRow
                pushed = newPushed
            }
            hands[playerId] = pushed.shr(shift)
        }

        fun pushUp(idx: Int, rows: LongArray, hands: LongArray, playerId: Int) {
            val mask = MASK[idx]
            val shift = (6 - idx) * 9
            var pushed = hands[playerId].shl(shift)
            for (i in (6 downTo 0)) {
                val row = rows[i]
                val newPushed = row.and(mask)
                val newRow = row.xor(newPushed).or(pushed)
                rows[i] = newRow
                pushed = newPushed
            }
            hands[playerId] = pushed.shr(shift)
        }
    }

    fun get(y: Int, x: Int) = bitField(getField(y, x))
    operator fun get(point: Point) = get(point.y, point.x)

    fun ourField(): BitField {
        return bitField(hands[0])
    }

    fun enemyField(): BitField {
        return bitField(hands[1])
    }

    fun field(playerId: Int) = bitField(hands[playerId])

    private fun getField(y: Int, x: Int): Long {
        return rows[y].and(MASK[x]).shr((6 - x) * 9)
    }

    fun canUp(x: Int, y: Int) = (y > 0) && BitField.connected(getField(y, x), UP, getField(y - 1, x))
    fun canRight(x: Int, y: Int) = (x < 6) && BitField.connected(getField(y, x), RIGHT, getField(y, x + 1))
    fun canDown(x: Int, y: Int) = (y < 6) && BitField.connected(getField(y, x), DOWN, getField(y + 1, x))
    fun canLeft(x: Int, y: Int) = (x > 0) && BitField.connected(getField(y, x), LEFT, getField(y, x - 1))

    override fun toString(): String {
        return (0..6).joinToString(separator = "\n") { y ->
            (0..6).joinToString(separator = " ") { x ->
                val field = getField(y, x)
                (field and BitField.TILE_MASK).toString(2).padStart(4, '0')
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BitBoard

        if (!rows.contentEquals(other.rows)) return false
        if (!hands.contentEquals(other.hands)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rows.contentHashCode()
        result = 31 * result + hands.contentHashCode()
        return result
    }
}

data class GameBoard(val bitBoard: BitBoard) {
    val domains = Domains()
    private var parent: GameBoard? = null
    private var pushesFromParent: Pushes? = null
    private var pushFromParent: OnePush? = null

    companion object {
        val pooledList1 = arrayListOf<PathElem>()
        val pooledList2 = arrayListOf<PathElem>()
        val pooledFront: Array<Point?> = Array(50) { null }
        var pooledFrontReadPos = 0
        var pooledFrontWritePos = 0

        fun readNextFront(): Point? {
            return if (pooledFrontWritePos == pooledFrontReadPos) {
                null
            } else {
                val result = pooledFront[pooledFrontReadPos]
                pooledFrontReadPos++
                result
            }
        }

        fun writeNextFront(point: Point) {
            pooledFront[pooledFrontWritePos] = point
            pooledFrontWritePos++
        }

        fun cleanFront() {
            pooledFrontReadPos = 0
            pooledFrontWritePos = 0
        }
    }

    fun findDomain(
        point: Point,
        ourQuestsSet: Int,
        enemyQuestsSet: Int
    ): DomainInfo {
        val cachedValue = domains.get(point, ourQuestsSet, enemyQuestsSet)
        if (cachedValue != null) {
            super_cached++
            return cachedValue
        }

        if (parent != null) {
            if (pushesFromParent != null) {
                val cachedValue = parent!!.domains.get(point, ourQuestsSet, enemyQuestsSet)
                if (cachedValue != null) {
                    val pushes = pushesFromParent!!
                    val affectOur = affectPush(pushes.ourPush, cachedValue)
                    val affectEnemy = affectPush(pushes.enemyPush, cachedValue)
                    if (!(affectEnemy || affectOur)) {
                        cached++
                        return cachedValue
                    }
                }
            } else if (pushFromParent != null) {
                val cachedValue = parent!!.domains.get(point, ourQuestsSet, enemyQuestsSet)
                if (cachedValue != null && !affectPush(pushFromParent!!, cachedValue)) {
                    cached++
                    return cachedValue
                }
            }
        }
        non_cached++
        var visitedPoints = 0L

        var ourQuestsBits = 0
        var enemyQuestsBits = 0
        var ourItems = 0
        var enemyItems = 0

        visitedPoints = visitedPoints.set(point.idx)
        cleanFront()
        writeNextFront(point)
        var maxX = 0
        var maxY = 0
        var minX = 7
        var minY = 7
        var twoPathsTileCount = 0
        var threePathsTileCount = 0
        var fourPathsTileCount = 0
        while (true) {
            val nextPoint = readNextFront() ?: break
            maxX = max(maxX, nextPoint.x)
            minX = min(minX, nextPoint.x)
            maxY = max(maxY, nextPoint.y)
            minY = min(minY, nextPoint.y)
            val bitField = bitBoard[nextPoint]

            when (Integer.bitCount(bitField.tile)) {
                2 -> twoPathsTileCount++
                3 -> threePathsTileCount++
                4 -> fourPathsTileCount++
            }

            val item = bitField.item
            if (item > 0) {
                if (ourQuestsSet[item]) {
                    ourQuestsBits = ourQuestsBits.set(item)
                }
                ourItems = ourItems.set(item)
            }
            if (item < 0) {
                if (enemyQuestsSet[-item]) {
                    enemyQuestsBits = enemyQuestsBits.set(-item)
                }
                enemyItems = enemyItems.set(-item)
            }
            for (direction in Tile.tiles[bitField.tile].directions) {
                if (nextPoint.can(direction)) {
                    val newPoint = nextPoint.move(direction)
                    if (!visitedPoints[newPoint.idx]) {
                        visitedPoints = visitedPoints.set(newPoint.idx)
                        writeNextFront(newPoint)
                    }
                }
            }
        }
        val domain = DomainInfo(
            inputOurQuests = ourQuestsSet,
            inputEnemyQuests = enemyQuestsSet,
            ourQuestBits = ourQuestsBits,
            enemyQuestBits = enemyQuestsBits,
            ourItemsBits = ourItems,
            enemyItemsBits = enemyItems,
            domainBits = visitedPoints,
            twoPathsTileCount = twoPathsTileCount,
            threePathsTileCount = threePathsTileCount,
            fourPathsTileCount = fourPathsTileCount,
            maxX = maxX,
            maxY = maxY,
            minX = minX,
            minY = minY
        )
        domains.set(domain)

        return domain
    }

    private fun affectPush(push: OnePush, cachedValue: DomainInfo): Boolean {
        val affectOurX = push.direction.isVertical
                && cachedValue.maxX + 1 >= push.rowColumn
                && cachedValue.minX - 1 <= push.rowColumn
        val affectOurY = !push.direction.isVertical
                && cachedValue.maxY + 1 >= push.rowColumn
                && cachedValue.minY - 1 <= push.rowColumn
        val affectOur = affectOurX || affectOurY
        return affectOur
    }

    operator fun get(point: Point) = bitBoard[point]

    fun findPaths(player: Player, quests: Int): List<PathElem> {
        fun coordInVisited(newPoint: Point, newItemsSet: Int): Int {
            val x = newPoint.x
            val y = newPoint.y
            var firstItem = 0
            var secondItem = 0
            var thirdItem = 0
            val firstQuestIdx = quests.nextSetBit(0)
            if (firstQuestIdx >= 0) {
                if (newItemsSet[firstQuestIdx]) {
                    firstItem = 1
                }
                val secondQuestIdx = quests.nextSetBit(firstQuestIdx + 1)
                if (secondQuestIdx >= 0) {
                    if (newItemsSet[secondQuestIdx]) {
                        secondItem = 1
                    }

                    val thirdQuestIdx = quests.nextSetBit(secondQuestIdx + 1)
                    if (thirdQuestIdx >= 0) {
                        if (newItemsSet[thirdQuestIdx]) {
                            thirdItem = 1
                        }
                    }
                }
            }

            return (((x * 7 + y) * 2 + firstItem) * 2 + secondItem) * 2 + thirdItem
        }

        val initialItem = bitBoard[player.point].item
        val initial =
            if (initialItem > 0 && quests[initialItem]) {
                PathElem(player.point, 0.set(initialItem), null, null)
            } else {
                PathElem(player.point, 0, null, null)
            }


        pooledList1.clear()
        pooledList2.clear()
        var front = pooledList1
        front.add(initial)

        val result = mutableListOf<PathElem>()

        val visited =
            BitSet((((6 * 7 + 6) * 2 + 1) * 2 + 1) * 2 + 1) // (((x * 7 + y) * 2 + firstItem) * 2 + secondItem) * 2 + thirdItem
        visited.set(coordInVisited(initial.point, initial.itemsTakenSet))
        result.add(initial)

        repeat(20) {
            if (front.isEmpty()) {
                return@repeat
            }

            val newFront = if (front === pooledList1) pooledList2 else pooledList1
            newFront.clear()

            for (pathElem in front) {
                for (direction in Direction.allDirections) {
                    if (!pathElem.point.can(direction)) {
                        continue
                    }
                    val newPoint = pathElem.point.move(direction)
                    val field = bitBoard[newPoint]
                    val newItems = if (field.containsQuestItem(player.playerId, quests)) {
                        pathElem.itemsTakenSet.set(field.item.absoluteValue)
                    } else {
                        pathElem.itemsTakenSet
                    }

                    val coordInVisisted = coordInVisited(newPoint, newItems)

                    if (visited.get(coordInVisisted)) {
                        continue
                    }

                    val newPathElem = PathElem(newPoint, newItems, pathElem, direction)
                    visited.set(coordInVisisted)
                    result.add(newPathElem)
                    newFront.add(newPathElem)
                }
            }
            front = newFront
        }

        return result
    }

    fun push(push: OnePush, player: Player): GameBoard {
        val rows = bitBoard.rows.clone()
        val hands = bitBoard.hands.clone()
        pushImpl(push, player.playerId, rows, hands)
        return GameBoard(BitBoard(rows, hands)).apply {
            parent = this@GameBoard
            pushesFromParent = null
            pushFromParent = push
        }
    }

    private fun pushImpl(push: OnePush, playerId: Int, rows: LongArray, hands: LongArray) {
        val direction = push.direction
        val rowColumn = push.rowColumn
        if (direction == LEFT || direction == RIGHT) {
            if (direction == LEFT) {
                BitBoard.pushLeft(rowColumn, rows, hands, playerId)
            } else {
                BitBoard.pushRight(rowColumn, rows, hands, playerId)
            }
        } else {
            if (direction == UP) {
                BitBoard.pushUp(rowColumn, rows, hands, playerId)
            } else {
                BitBoard.pushDown(rowColumn, rows, hands, playerId)
            }
        }
    }

    fun score(
        ourPlayer: Player,
        enemyPlayer: Player,
        collision: Boolean,
        ourDomain: DomainInfo = findDomain(ourPlayer.point, ourPlayer.currentQuests, enemyPlayer.currentQuests),
        enemyDomain: DomainInfo = findDomain(enemyPlayer.point, ourPlayer.currentQuests, enemyPlayer.currentQuests)
    ): Double {

        val ourItemRemain = ourPlayer.numPlayerCards - ourDomain.getOurQuestsCount
        val enemyItemRemain = enemyPlayer.numPlayerCards - enemyDomain.getEnemyQuestsCount

        if (ourItemRemain == 0) {
            if (enemyItemRemain == 0) {
                val enemyPushToLastQuest = enemyPlayer.numPlayerCards == 1
                        && this[enemyPlayer.point].item < 0
                val ourPushToLastQuest = ourPlayer.numPlayerCards == 1
                        && this[ourPlayer.point].item > 0
                if (enemyPushToLastQuest.xor(ourPushToLastQuest)) {
                    if (enemyPushToLastQuest) {
                        return 0.0
                    } else {
                        return 1.0
                    }
                }
                return 0.5
            } else {
                return 1.0
            }
        }
        if (enemyItemRemain == 0) {
            return 0.0
        }
        val ourFieldOnHand = bitBoard.ourField()
        val enemyFieldOnHand = bitBoard.enemyField()

        var ourAdditionalQuest = 0
        var ourHiddenQuestsCount = 0
        var ourNonQuestItemsCount = 0
        run {
            ourPlayer.takeQuests(ourDomain.ourQuestBits) { nextOurPlayer ->
                val domainHasNextQuests = (ourDomain.ourItemsBits and nextOurPlayer.currentQuests) != 0
                if (domainHasNextQuests) {
                    ourAdditionalQuest = 1
                }
                if (nextOurPlayer.currentQuestsCount < min(3, nextOurPlayer.numPlayerCards)) {
                    val quests = nextOurPlayer.alreadyTakenQuests.or(nextOurPlayer.currentQuests)
                    ourNonQuestItemsCount = (ourDomain.ourItemsBits and quests.inv()).bitCount()
                    ourHiddenQuestsCount = nextOurPlayer.numPlayerCards - nextOurPlayer.currentQuestsCount
                }
            }
        }

        var enemyAdditionalQuest = 0
        var enemyHiddenQuestsCount = 0
        var enemyNonQuestItemsCount = 0
        run {
            enemyPlayer.takeQuests(enemyDomain.enemyQuestBits) { nextEnemyPlayer ->
                val domainHasNextQuests = (enemyDomain.enemyItemsBits and nextEnemyPlayer.currentQuests) != 0
                if (domainHasNextQuests) {
                    enemyAdditionalQuest = 1
                }
                if (nextEnemyPlayer.currentQuestsCount < min(3, nextEnemyPlayer.numPlayerCards)) {
                    val quests = nextEnemyPlayer.alreadyTakenQuests.or(nextEnemyPlayer.currentQuests)
                    enemyNonQuestItemsCount = (enemyDomain.enemyItemsBits and quests.inv()).bitCount()
                    enemyHiddenQuestsCount = nextEnemyPlayer.numPlayerCards - nextEnemyPlayer.currentQuestsCount
                }
            }
        }

        val maxSpaceScore:Int
        val spaceScore:Int
        val maxHandScore:Int
        if (Tweaks.useRoadsForSecondaryScore) {
            spaceScore = ourDomain.tilePathsCount - enemyDomain.tilePathsCount
            maxSpaceScore = 115
            maxHandScore = 25
        } else {
            spaceScore = ourDomain.size - enemyDomain.size
            maxSpaceScore = 48
            maxHandScore = 12
        }
        val ourHandScore = PushSelectors.itemOnHandScore(ourPlayer, ourDomain, ourFieldOnHand)
        val enemyHandScore = PushSelectors.itemOnHandScore(enemyPlayer, enemyDomain, enemyFieldOnHand)
        var secondaryScore = ((spaceScore + (ourHandScore - enemyHandScore) * maxHandScore)).toDouble() / (maxSpaceScore + maxHandScore)
        secondaryScore = Math.signum(secondaryScore) * Math.sqrt(secondaryScore.absoluteValue)

        val pushesRemain = if (collision && numberOfDraws != 0) {
            9 - numberOfDraws
        } else {
            Math.max(pushesRemain - 1, 0)
        }
        run {
            val ourItemRemain = ourItemRemain - ourAdditionalQuest
            val enemyItemRemain = enemyItemRemain - enemyAdditionalQuest
            if (ourItemRemain == 0) {
                if (enemyItemRemain == 0) {
                    val enemyPushToLastQuest = enemyPlayer.numPlayerCards == 1
                            && this[enemyPlayer.point].item < 0
                    val ourPushToLastQuest = ourPlayer.numPlayerCards == 1
                            && this[ourPlayer.point].item > 0
                    if (enemyPushToLastQuest.xor(ourPushToLastQuest)) {
                        if (enemyPushToLastQuest) {
                            return 0.0
                        } else {
                            return 1.0
                        }
                    }
                    return 0.5
                } else {
                    return 1.0
                }
            }
            if (enemyItemRemain == 0) {
                return 0.0
            }
        }
        val estimate = computeEstimate(
            ourItemRemain = ourItemRemain - ourAdditionalQuest,
            enemyItemRemain = enemyItemRemain - enemyAdditionalQuest,
            pushesRemain = pushesRemain,
            secondaryScore = secondaryScore
        )
        var result = estimate
        val weTake = ourDomain.getOurQuestsCount + ourAdditionalQuest +
                (min(3, ourPlayer.numPlayerCards) - ourPlayer.currentQuestsCount)
        val enemyTake = enemyDomain.getEnemyQuestsCount + enemyAdditionalQuest +
                (min(3, enemyPlayer.numPlayerCards) - enemyPlayer.currentQuestsCount)
        if ((weTake > 0 || enemyTake > 0)) {
            var weTakeProbability = 0.0
            var enemyTakeProbability = 0.0
            if (ourAdditionalQuest == 0 && ourHiddenQuestsCount > 0) {
                weTakeProbability = ourNonQuestItemsCount.toDouble() / ourHiddenQuestsCount
            }
            if (enemyAdditionalQuest == 0 && enemyHiddenQuestsCount > 0) {
                enemyTakeProbability = enemyNonQuestItemsCount.toDouble() / enemyHiddenQuestsCount
            }
            if (weTakeProbability > 0 && enemyTakeProbability > 0) {
                val weTakeEstimate = computeEstimate(
                    ourItemRemain = ourItemRemain - ourAdditionalQuest - 1,
                    enemyItemRemain = enemyItemRemain - enemyAdditionalQuest,
                    pushesRemain = pushesRemain,
                    secondaryScore = secondaryScore
                )
                val enemyTakeEstimate = computeEstimate(
                    ourItemRemain = ourItemRemain - ourAdditionalQuest,
                    enemyItemRemain = enemyItemRemain - enemyAdditionalQuest - 1,
                    pushesRemain = pushesRemain,
                    secondaryScore = secondaryScore
                )
                val bothTakeEstimate = computeEstimate(
                    ourItemRemain = ourItemRemain - ourAdditionalQuest - 1,
                    enemyItemRemain = enemyItemRemain - enemyAdditionalQuest - 1,
                    pushesRemain = pushesRemain,
                    secondaryScore = secondaryScore
                )
                result = weTakeEstimate * weTakeProbability * (1 - enemyTakeProbability) +
                        enemyTakeEstimate * (1 - weTakeProbability) * enemyTakeProbability +
                        bothTakeEstimate * weTakeProbability * enemyTakeProbability +
                        estimate * (1 - weTakeProbability) * (1 - enemyTakeProbability)
            } else if (weTakeProbability > 0) {
                val weTakeEstimate = computeEstimate(
                    ourItemRemain = ourItemRemain - ourAdditionalQuest - 1,
                    enemyItemRemain = enemyItemRemain - enemyAdditionalQuest,
                    pushesRemain = pushesRemain,
                    secondaryScore = secondaryScore
                )

                result = weTakeEstimate * weTakeProbability + estimate * (1 - weTakeProbability)
            } else if (enemyTakeProbability > 0) {
                val enemyTakeEstimate = computeEstimate(
                    ourItemRemain = ourItemRemain - ourAdditionalQuest,
                    enemyItemRemain = enemyItemRemain - enemyAdditionalQuest - 1,
                    pushesRemain = pushesRemain,
                    secondaryScore = secondaryScore
                )
                result = enemyTakeEstimate * enemyTakeProbability + estimate * (1 - enemyTakeProbability)
            }
        }

        if (result < 0 || result > 1) {
            log("!!! unprobable $result")
        }
        return result
    }

    fun push(pushes: Pushes): GameBoard {
        if (pushes.collision) {
            return this
        }

        val rows = bitBoard.rows.clone()
        val hands = bitBoard.hands.clone()
        pushImpl(pushes.firstPush, pushes.firstPlayerId, rows, hands)
        pushImpl(pushes.secondPush, pushes.secondPlayerId, rows, hands)

        return GameBoard(BitBoard(rows, hands)).apply {
            parent = this@GameBoard
            pushesFromParent = pushes
            pushFromParent = null
        }
    }


    private fun Point.can(direction: Direction) = when (direction) {
        UP -> canUp(this)
        DOWN -> canDown(this)
        LEFT -> canLeft(this)
        RIGHT -> canRight(this)
    }

    private fun canUp(point: Point) = bitBoard.canUp(point.x, point.y)
    private fun canRight(point: Point) = bitBoard.canRight(point.x, point.y)
    private fun canDown(point: Point) = bitBoard.canDown(point.x, point.y)
    private fun canLeft(point: Point) = bitBoard.canLeft(point.x, point.y)
}

private fun Int.nextSetBit(fromIndex: Int): Int {
    for (result in (fromIndex until 32)) {
        if (this[result]) {
            return result
        }
    }
    return -1
}

private operator fun Array<IntArray>.set(point: Point, value: Int) {
    this[point.y][point.x] = value
}

private operator fun <T> List<List<T>>.get(point: Point): T {
    return this[point.y][point.x]
}


@Suppress("DataClassPrivateConstructor")
data class Point private constructor(val x: Int, val y: Int) {
    val idx = y * 7 + x
    var up: Point? = null
    var down: Point? = null
    var left: Point? = null
    var right: Point? = null

    fun move(direction: Direction) = when (direction) {
        UP -> up!!
        DOWN -> down!!
        LEFT -> left!!
        RIGHT -> right!!
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Point

        if (x != other.x) return false
        if (y != other.y) return false

        return true
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        return result
    }

    override fun toString(): String = "($x,$y)"


    companion object {
        val points = Array(49) { idx ->
            val x = idx % 7
            val y = idx / 7
            Point(x, y)
        }

        private val point_minus2 = Point(-2, -2)
        private val point_minus1 = Point(-1, -1)

        init {
            (0..6).forEach { x ->
                (0..6).forEach { y ->
                    if (x > 0) {
                        point(x, y).left = point(x - 1, y)
                    }
                    if (x < 6) {
                        point(x, y).right = point(x + 1, y)
                    }
                    if (y > 0) {
                        point(x, y).up = point(x, y - 1)
                    }
                    if (y < 6) {
                        point(x, y).down = point(x, y + 1)
                    }
                }
            }
        }

        fun point(x: Int, y: Int): Point {
            return when {
                x >= 0 -> points[y * 7 + x]
                x == -2 -> point_minus2
                else -> point_minus1
            }
        }
    }
}

@Suppress("unused")
enum class Tile(val mask: Long, val roads: Int) {
    T0000(0b0000, 0),
    T0011(0b0011, 2),
    T0101(0b0101, 2),
    T0110(0b0110, 2),
    T1001(0b1001, 2),
    T1010(0b1010, 2),
    T1100(0b1100, 2),
    T0111(0b0111, 3),
    T1011(0b1011, 3),
    T1101(0b1101, 3),
    T1110(0b1110, 3),
    T1111(0b1111, 4);

    val directions = Direction.allDirections.filter { it.mask and mask != 0L }.toTypedArray()

    companion object {
        fun read(input: Scanner): Tile {
            val mask = java.lang.Long.parseLong(input.next(), 2)
            return values().find { it.mask == mask }!!
        }

        val tiles = Array(16) { idx -> values().find { it.mask == idx.toLong() } ?: T0000 }
    }

    fun connect(tile: Tile, direction: Direction): Boolean {
        return this.mask.and(direction.mask) != 0L && tile.mask.and(direction.opposite.mask) != 0L
    }
}

class Player(
    var playerId: Int,
    var numPlayerCards: Int,
    var playerX: Int,
    var playerY: Int,
    var alreadyTakenQuests: Int,
    var currentQuests: Int,
    var lastQuestIdx: Int,
    var point: Point = Point.point(playerX, playerY),
    var currentQuestsCount: Int = currentQuests.bitCount()
) {
    constructor(playerId: Int, input: Scanner) : this(
        playerId = playerId,
        numPlayerCards = input.nextInt(), // the total number of quests for a player (hidden and revealed)
        playerX = input.nextInt(),
        playerY = input.nextInt(),
        alreadyTakenQuests = 0, //to be set
        currentQuests = 0,//to be set
        lastQuestIdx = 0//to be set
    )


    internal inline fun <T> push(
        pushPlayerId: Int,
        push: OnePush,
        board: GameBoard,
        crossinline block: (Player) -> T
    ): T {
        var x = playerX
        var y = playerY
        val firstPushField = if (pushPlayerId == 0) board.bitBoard.ourField() else board.bitBoard.enemyField()
        var questsToTake = 0
        push.run {
            if (direction.isVertical) {
                if (x == rowColumn) {
                    val wasOnBorder = (y == 0 || y == 6)
                    y = (y + (if (direction == UP) -1 else 1) + 7) % 7
                    val nowOnBorder = (y == 0 || y == 6)
                    if (wasOnBorder && nowOnBorder && firstPushField.containsQuestItem(
                            pushPlayerId,
                            currentQuests
                        )
                    ) {
                        questsToTake = questsToTake.set(firstPushField.item.absoluteValue)
                    }
                }
            } else {
                if (y == rowColumn) {
                    val wasOnBorder = (x == 0 || x == 6)
                    x = (x + (if (direction == LEFT) -1 else 1) + 7) % 7
                    val nowOnBorder = (x == 0 || x == 6)
                    if (wasOnBorder && nowOnBorder && firstPushField.containsQuestItem(
                            pushPlayerId,
                            currentQuests
                        )
                    ) {
                        questsToTake = questsToTake.set(firstPushField.item.absoluteValue)
                    }
                }
            }
        }
        return if (x != playerX || y != playerY) {
            copy(point = Point.point(x, y)) {
                it.takeQuests(questsToTake, block)
            }
        } else {
            this.takeQuests(questsToTake, block)
        }
    }

    internal inline fun <T> push(pushes: Pushes, gameBoard: GameBoard, crossinline block: (Player) -> T): T {
        return copy {
            it.push_inPlace(pushes, gameBoard)
            block(it)
        }
    }

    internal inline fun <T> push(
        startPoint: Point,
        pushes: Pushes,
        gameBoard: GameBoard,
        crossinline block: (Player) -> T
    ): T {
        return copy(point = startPoint) {
            it.push_inPlace(pushes, gameBoard)
            block(it)
        }
    }

    fun push_inPlace(pushes: Pushes, gameBoard: GameBoard) {
        if (pushes.collision) {
            return
        }
        var x = playerX
        var y = playerY
        val firstPush = pushes.firstPush
        val secondPush = pushes.secondPush
        val firstPushField = gameBoard.bitBoard.field(pushes.firstPlayerId)
        val secondPushField = gameBoard.bitBoard.field(pushes.secondPlayerId)

        var questsToTake = 0

        firstPush.run {
            if (direction.isVertical) {
                if (x == rowColumn) {
                    val wasOnBorder = (y == 0 || y == 6)
                    y = (y + (if (direction == UP) -1 else 1) + 7) % 7
                    val nowOnBorder = (y == 0 || y == 6)
                    if (wasOnBorder && nowOnBorder && firstPushField.containsQuestItem(playerId, currentQuests)) {
                        questsToTake = questsToTake.set(firstPushField.item.absoluteValue)
                    }
                }
            } else {
                if (y == rowColumn) {
                    val wasOnBorder = (x == 0 || x == 6)
                    x = (x + (if (direction == LEFT) -1 else 1) + 7) % 7
                    val nowOnBorder = (x == 0 || x == 6)
                    if (wasOnBorder && nowOnBorder && firstPushField.containsQuestItem(playerId, currentQuests)) {
                        questsToTake = questsToTake.set(firstPushField.item.absoluteValue)
                    }
                }
            }
        }

        secondPush.run {
            if (direction.isVertical) {
                if (x == rowColumn) {
                    val wasOnBorder = (y == 0 || y == 6)
                    y = (y + (if (direction == UP) -1 else 1) + 7) % 7
                    val nowOnBorder = (y == 0 || y == 6)
                    if (wasOnBorder && nowOnBorder && secondPushField.containsQuestItem(playerId, currentQuests)) {
                        questsToTake = questsToTake.set(secondPushField.item.absoluteValue)
                    }
                }
            } else {
                if (y == rowColumn) {
                    val wasOnBorder = (x == 0 || x == 6)
                    x = (x + (if (direction == LEFT) -1 else 1) + 7) % 7
                    val nowOnBorder = (x == 0 || x == 6)
                    if (wasOnBorder && nowOnBorder && secondPushField.containsQuestItem(playerId, currentQuests)) {
                        questsToTake = questsToTake.set(secondPushField.item.absoluteValue)
                    }
                }
            }
        }

        this.apply {
            playerX = x
            playerY = y
            point = Point.point(playerX, playerY)
            takeQuests__inPlace(questsToTake)
        }
    }

    internal inline fun <T> takeQuests(quests: Int, crossinline block: (Player) -> T): T {
        return copy {
            it.takeQuests__inPlace(quests)
            block(it)
        }
    }

    private fun takeQuests__inPlace(quests: Int) {
        if (quests == 0) {
            return
        }
        val takenCount = Integer.bitCount(quests)
        val newQuests = 0b1111_1111_1111_0 and when (takenCount) {
            1 -> 0.set(Quests.questByIdx(lastQuestIdx + 1))
            2 -> 0.set(Quests.questByIdx(lastQuestIdx + 1)) or
                    0.set(Quests.questByIdx(lastQuestIdx + 2))
            3 -> 0.set(Quests.questByIdx(lastQuestIdx + 1)) or
                    0.set(Quests.questByIdx(lastQuestIdx + 2)) or
                    0.set(Quests.questByIdx(lastQuestIdx + 3))
            4 -> 0.set(Quests.questByIdx(lastQuestIdx + 1)) or
                    0.set(Quests.questByIdx(lastQuestIdx + 2)) or
                    0.set(Quests.questByIdx(lastQuestIdx + 3)) or
                    0.set(Quests.questByIdx(lastQuestIdx + 4))
            else -> throw Exception("Too many taken quests $takenCount")
        }
        this.apply {
            numPlayerCards = numPlayerCards - takenCount
            alreadyTakenQuests = alreadyTakenQuests or quests
            currentQuests = (currentQuests and quests.inv()) or newQuests
            currentQuestsCount = currentQuests.bitCount()
            lastQuestIdx = lastQuestIdx + takenCount
        }
    }

    inline fun <T> copy(
        numPlayerCards: Int = this.numPlayerCards,
        point: Point = this.point,
        alreadyTakenQuests: Int = this.alreadyTakenQuests,
        currentQuests: Int = this.currentQuests,
        lastQuestIdx: Int = this.lastQuestIdx,
        crossinline block: (Player) -> T
    ): T {
        val idx = indexInCache
        indexInCache++
        val result = block(
            cache[idx].apply {
                this.copy_inPlace(
                    this@Player,
                    numPlayerCards,
                    point,
                    alreadyTakenQuests,
                    currentQuests,
                    lastQuestIdx
                )
            }
        )
        indexInCache--
        return result
    }

    fun copy_inPlace(
        orig: Player,
        numPlayerCards: Int = orig.numPlayerCards,
        point: Point = orig.point,
        alreadyTakenQuests: Int = orig.alreadyTakenQuests,
        currentQuests: Int = orig.currentQuests,
        lastQuestIdx: Int = orig.lastQuestIdx
    ) {
        this.playerId = orig.playerId
        this.numPlayerCards = numPlayerCards
        this.playerX = point.x
        this.playerY = point.y
        this.alreadyTakenQuests = alreadyTakenQuests
        this.currentQuests = currentQuests
        this.lastQuestIdx = lastQuestIdx
        this.point = point
        this.currentQuestsCount = currentQuests.bitCount()
    }

    fun escapeCopy(): Player {
        return Player(playerId, numPlayerCards, playerX, playerY, alreadyTakenQuests, currentQuests, lastQuestIdx)
    }

    companion object {
        var indexInCache: Int = 0
        val cache = Array(100) { Player(0, 0, 0, 0, 0, 0, 0) }
    }
}

data class ItemDto(val itemName: String, val itemX: Int, val itemY: Int, val itemPlayerId: Int) {
    constructor(input: Scanner) : this(
        itemName = input.next(),
        itemX = input.nextInt(),
        itemY = input.nextInt(),
        itemPlayerId = input.nextInt()
    )

    val isOnOurHand: Boolean = itemX == -1
    val isOnEnemyHand: Boolean = itemX == -2

    fun toItem() =
        (Items.index(itemName)) * (if (itemPlayerId == 0) 1 else -1)
}

class QuestDto(input: Scanner) {
    val questItemName: String = input.next()
    val questPlayerId = input.nextInt()
}

enum class Direction(val mask: Long, val isVertical: Boolean, val priority: Int) {
    UP(0b1000, true, priority = 0),
    RIGHT(0b0100, false, priority = 1),
    DOWN(0b0010, true, priority = 0),
    LEFT(0b0001, false, priority = 1);

    lateinit var opposite: Direction

    companion object {
        val allDirections = values().toList()

        init {
            allDirections.forEach {
                it.opposite = when (it) {
                    UP -> DOWN
                    DOWN -> UP
                    LEFT -> RIGHT
                    RIGHT -> LEFT
                }
            }

        }
    }
}

class TeeInputStream(private var source: InputStream, private var copySink: OutputStream) : InputStream() {
    @Throws(IOException::class)
    override fun read(): Int {
        val result = source.read()
        if (result >= 0) {
            copySink.write(result)
        }
        return result
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return source.available()
    }

    @Throws(IOException::class)
    override fun close() {
        source.close()
    }

    @Synchronized
    override fun mark(readlimit: Int) {
        source.mark(readlimit)
    }

    override fun markSupported(): Boolean {
        return source.markSupported()
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val result = source.read(b, off, len)
        if (result >= 0) {
            copySink.write(b, off, result)
        }
        return result
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray): Int {
        val result = source.read(b)
        if (result >= 0) {
            copySink.write(b, 0, result)
        }
        return result
    }

    @Synchronized
    @Throws(IOException::class)
    override fun reset() {
        source.reset()
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        return source.skip(n)
    }
}

object Warmup {
    init {
        log("warmup data read")
    }

    fun warmupOnce() {
        log("warmup once started")
        lastPush = findBestPush(
            toPush.we,
            toPush.enemy,
            toPush.gameBoard // big limit
        )
        lastBoard = toPush.gameBoard
        lastBoardAndElves = BoardAndElves(toPush.gameBoard, toPush.we.point, toPush.enemy.point)
        log("warmup once move")
        processPreviousPush(
            toMove.gameBoard,
            toMove.we,
            toMove.enemy,
            mutableMapOf()
        )
        findBestMove(
            gameBoard = toMove.gameBoard,
            we = toMove.we,
            step = 1,
            enemy = toMove.enemy
        )
    }

    private val warmupMove = """
1
0011 1010 0110 0111 0011 1010 1010
1010 1101 0110 1010 0111 0110 0110
0101 1101 1001 1001 0101 1010 1110
1011 1101 1010 1010 0101 0101 1010
1101 1001 0101 1101 0101 1001 1001
0111 1100 1001 0111 1001 0111 1100
0110 1111 0110 0101 1010 1101 0111
9 5 3 0110
6 1 5 0101
15
SWORD 5 5 0
MASK 6 2 1
KEY 2 5 0
FISH 4 0 1
SHIELD 4 3 1
CANDY 2 6 0
KEY -1 -1 1
MASK 0 3 0
SWORD 1 3 1
SCROLL 0 0 0
ARROW 6 4 1
SHIELD 5 3 0
POTION 5 4 0
FISH 1 5 0
ARROW 5 1 0
6
SCROLL 0
CANDY 0
KEY 0
KEY 1
SHIELD 1
FISH 1
""".trimIndent()

    private val warmupPush = """
0
0110 1010 1101 0101 0011 1010 0011
0110 0101 0101 0111 1001 0110 1101
1010 1010 0110 0101 1001 0110 0111
1011 1101 1101 1111 0111 0111 1110
1101 1001 0110 0101 1001 1010 1010
0111 1001 0110 1101 0101 0101 1001
1100 1010 1100 0101 0111 1010 1001
12 6 6 1010
12 0 0 1010
24
POTION 2 4 1
SWORD 5 3 0
MASK 6 3 1
DIAMOND 6 4 0
KEY 4 1 0
BOOK 3 0 0
FISH 4 0 1
SHIELD 3 4 1
CANE 5 6 0
CANDY 5 1 0
BOOK 3 6 1
CANDY 1 5 1
KEY 2 5 1
CANE 1 0 1
MASK 0 3 0
SWORD 1 3 1
SCROLL 6 0 0
ARROW 6 5 1
SHIELD 3 2 0
SCROLL 0 6 1
POTION 4 2 0
FISH 2 6 0
DIAMOND 0 2 1
ARROW 0 1 0
6
SCROLL 0
CANE 0
CANDY 0
SCROLL 1
CANE 1
CANDY 1 
""".trimIndent()

    val toPush = readInput(Scanner(StringReader(warmupPush)))
    val toMove = readInput(Scanner(StringReader(warmupMove)))
}

object Items {
    val items = arrayOf(
        "ARROW",
        "BOOK",
        "CANDY",
        "CANE",
        "DIAMOND",
        "FISH",
        "KEY",
        "MASK",
        "POTION",
        "SCROLL",
        "SHIELD",
        "SWORD"
    )

    fun index(name: String) = items.indexOf(name) + 1

    fun name(index: Int) = items[index - 1]

    fun Int.indexesToNames(): List<String> {
        val result = mutableListOf<String>()
        for (itemName in items) {
            if (this[index(itemName)]) {
                result.add(itemName)
            }
        }
        return result
    }

    val NO_ITEM = 0
}

object Tweaks {
    val scoreCollisionOnlyForPreviousPushes = true
    val nearStrategiesThreshold = 1.0
    val useRoadsForSecondaryScore = true
    val earlyDrawPushesRemain = 75 //75 for turnoff
    val earlyDrawNumberOfDraws = 3
}

//we counted we counted our little fingers were tired
// @formatter:off
private val fingerprints: MutableMap<Fingerprint, DoubleArray> = mutableMapOf()