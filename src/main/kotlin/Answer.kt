@file:Suppress("NAME_SHADOWING", "UnnecessaryVariable")

import BitField.Companion.bitField
import Direction.*
import Items.NO_ITEM
import OnePush.Companion.onePush
import PushSelectors.itemOnHandScore
import PushSelectors.space
import RepetitionType.*
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

/**
 * Help the Christmas elves fetch presents in a magical labyrinth!
 **/
val startTime = System.currentTimeMillis()
fun log(s: Any?) {
    System.err.println("\n#[${System.currentTimeMillis() - startTime}] $s\n")
}

var super_cached = 0
var cached = 0
var non_cached = 0
var quests_not_match = 0

var maxDomains = 0

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

val drawRepetitions = Array<RepetitionType>(11) { UNKNOWN }
val nonDrawRepetitions = Array<RepetitionType>(150) { UNKNOWN }

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

val moveScores = Point.points.flatten()
    .map { it to 0.0 }
    .toMap().toMutableMap()
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

    globalQuestsInGameOrder.clear()

    try {
//        val input = Scanner(System.`in`)
        val input = Scanner(TeeInputStream(System.`in`, System.err))

//        val input = Scanner(
//            StringReader(
//                """""".trimIndent()
//            )
//        )

        // game loop

        val allBoards = mutableMapOf<BoardAndElves, MutableList<Pushes>>()

        repeat(150) { step ->
            pushesRemain = (150 - step) / 2 - 1

            val start = System.nanoTime()
            log("step $step")

            val (turnType, gameBoard, ourQuests, enemyQuests, we, enemy) = readInput(input)
            if (step == 0) {
                val fields: MutableList<BitField> = Point.points.flatten()
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
            }

            if (turnType == 0) {
                val duration = measureNanoTime {
                    val prevMovesAtThisPosition = allBoards[BoardAndElves(gameBoard, we.point, enemy.point)]

                    log("consecutiveDraws=$numberOfDraws drawsWOMoves=$numberOfDrawsWithoutMoves duplicate=${prevMovesAtThisPosition != null}")

                    val bestMove = findBestPush(
                        we,
                        enemy,
                        gameBoard,
                        ourQuests,
                        enemyQuests,
                        step,
                        prevMovesAtThisPosition,
                        numberOfDraws
                    )

                    lastBoard = gameBoard
                    lastBoardAndElves = BoardAndElves(gameBoard, we.point, enemy.point)
                    lastPush = bestMove
                }

                log("$step pushDuration: ${TimeUnit.NANOSECONDS.toMillis(duration)}")
                while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(30)) {
                    log("time to sleep")
                    Thread.sleep(10)
                }

                val bestMove = lastPush!!
                println("PUSH ${bestMove.rowColumn} ${bestMove.direction}")
            } else {
                var output: String? = null
                val duration = measureNanoTime {
                    val bestPath = findBestMove(
                        gameBoard,
                        allBoards,
                        we,
                        ourQuests,
                        step,
                        enemy,
                        enemyQuests
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

val globalQuestsInGameOrder = mutableListOf<Int>()

private fun findBestMove(
    gameBoard: GameBoard,
    allBoards: MutableMap<BoardAndElves, MutableList<Pushes>>,
    we: Player,
    ourQuests: Int,
    step: Int,
    enemy: Player,
    enemyQuests: Int
): PathElem? {
    log("findBestMove")
    val startTime = System.nanoTime()
    val lastPush = lastPush!!
    val lastBoard = lastBoard!!
    val lastBoardAndElves = lastBoardAndElves!!
    val wasDrawAtPrevMove = lastBoard == gameBoard
    val wasMove = we.point != lastBoardAndElves!!.ourElf || enemy.point != lastBoardAndElves!!.enemyElf
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
                val sameMoveAsPrev = prevMoves!!.last().ourPush.collision(enemyLastPush)
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

                allBoards.computeIfAbsent(lastBoardAndElves) { _ -> mutableListOf() }
                    .add(Pushes(lastPush, enemyLastPush))
            }
        }
    } else { // was draw
        if (wasDrawSequence && prevMoves != null) {
            val sameMoveAsPrev = prevMoves!!.last().ourPush.collision(lastPush)
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

        allBoards.computeIfAbsent(lastBoardAndElves) { _ -> mutableListOf() }
            .add(Pushes(lastPush, lastPush))
    }

    val ourPaths = gameBoard.findPaths(we, ourQuests)
    val ourItemsTaken = ourPaths.maxWith(compareBy { Integer.bitCount(it.itemsTakenSet) })!!.itemsTakenSet
    val ourItemsTakenSize = Integer.bitCount(ourItemsTaken)
    var ourNextQuests = ourQuests.and(ourItemsTaken.inv())
    if (ourItemsTakenSize > 0 && we.numPlayerCards > 3) {
        val nextQuestId = 12 - we.numPlayerCards + 3
        if (globalQuestsInGameOrder.size > nextQuestId) {
            ourNextQuests = ourNextQuests.set(globalQuestsInGameOrder[nextQuestId])

            if (ourItemsTakenSize > 1 && we.numPlayerCards > 4 && globalQuestsInGameOrder.size > nextQuestId + 1) {
                ourNextQuests = ourNextQuests.set(globalQuestsInGameOrder[nextQuestId + 1])

                if (ourItemsTakenSize > 2 && we.numPlayerCards > 5 && globalQuestsInGameOrder.size > nextQuestId + 2) {
                    ourNextQuests = ourNextQuests.set(globalQuestsInGameOrder[nextQuestId + 2])
                }
            }

        }
    }
    val ourNextNumCards = we.numPlayerCards - ourItemsTakenSize

    val enemyDomain = gameBoard.findDomain(enemy.point, ourQuests, enemyQuests)
    val enemyItemsTaken = enemyDomain.enemyQuestBits
    val enemyItemsTakenSize = Integer.bitCount(enemyItemsTaken)
    var enemyNextQuests = enemyQuests.and(enemyItemsTaken.inv())
    if (enemyItemsTakenSize > 0 && enemy.numPlayerCards > 3) {
        val nextQuestId = 12 - enemy.numPlayerCards + 3
        if (globalQuestsInGameOrder.size > nextQuestId) {
            enemyNextQuests = enemyNextQuests.set(globalQuestsInGameOrder[nextQuestId])

            if (enemyItemsTakenSize > 1 && enemy.numPlayerCards > 4 && globalQuestsInGameOrder.size > nextQuestId + 1) {
                enemyNextQuests = enemyNextQuests.set(globalQuestsInGameOrder[nextQuestId + 1])

                if (enemyItemsTakenSize > 2 && enemy.numPlayerCards > 5 && globalQuestsInGameOrder.size > nextQuestId + 2) {
                    enemyNextQuests = enemyNextQuests.set(globalQuestsInGameOrder[nextQuestId + 2])
                }
            }

        }
    }
    val enemyNextNumCards = enemy.numPlayerCards - enemyItemsTakenSize

    run {
        val ourQuests = Items.indexesToNames(ourNextQuests)
        val enemyQuests = Items.indexesToNames(enemyNextQuests)
        log("Our next quests: $ourQuests;  enemy next quests: $enemyQuests}")
    }

    val ends = ourPaths.filter { Integer.bitCount(it.itemsTakenSet) == ourItemsTakenSize }
        .map { it.point }.toHashSet()
    if (ends.size == 1) {
        return ourPaths.find { Integer.bitCount(it.itemsTakenSet) == ourItemsTakenSize }!!
    }
    ends.forEach { moveScores[it] = 0.0 }

    val timeLimit = TimeUnit.MILLISECONDS.toNanos(if (step == 0) 500 else 42)

    val possibleQuestCoef = if (we.numPlayerCards - Integer.bitCount(ourQuests) > 0) {
        ourItemsTakenSize * 1.0 / (we.numPlayerCards - Integer.bitCount(ourQuests))
    } else {
        0.0
    }
    var count = 0
    for (p in ends) {
        movePushScores[p.idx].fill(0.0)
    }
    val nextEnemy = enemy.copy(numPlayerCards = enemyNextNumCards)
    for (pushes in Pushes.allPushes) {
        if (System.nanoTime() - startTime > timeLimit * 2 && count > 0) {
            log("stop computePushes, computed $count pushes")
            break
        }
        count++

        val ourPlayer = we.push(pushes)
        val enemyPlayer = nextEnemy.push(pushes)
        val newBoard = gameBoard.push(pushes)
        val pushAndMove = PushAndMove(
            pushes = pushes,
            board = newBoard,
            ourPlayer = ourPlayer,
            enemyPlayer = enemyPlayer,
            ourQuests = ourNextQuests,
            enemyQuests = enemyNextQuests
        )
        if (pushAndMove.board === gameBoard) {
            continue
        }
        ends.forEach { point ->
            val fake = we.copy(playerX = point.x, playerY = point.y, numPlayerCards = ourNextNumCards).push(pushes)
            val score = pushAndMove.copy(ourPlayer = fake).score
            moveScores[point] = moveScores[point]!! + score
            movePushScores[point.idx][pushes.ourPush.idx] += score
        }

        maxDomains = max(maxDomains, pushAndMove.board.domains.count())
    }
    val maxScoreByPoint = movePushScores.map { it.max() }

    val scoreComparator = compareBy<PathElem> { pathElem ->
        moveScores[pathElem.point]!!
    }.thenComparing { pathElem ->
        max(2 * abs(pathElem.point.x - 3) + 1, 2 * abs(pathElem.point.y - 3))
    }.thenComparing { pathElem ->
        if (gameBoard.bitBoard.ourField().containsQuestItem(we.playerId, ourQuests)) {
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
    if (maxByAverageScore?.point != bestPath?.point) {
        log("MoveCandidate by average score ${maxByAverageScore?.point} differ from current best ${bestPath?.point}")
    }
    probablyLogCompilation()
    return bestPath
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
    val quests = (0 until input.nextInt()).map { Quest(input) }


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

    if (globalQuestsInGameOrder.size < 12) {
        ourQuests.forEach {
            val item = Items.index(it)
            if (!globalQuestsInGameOrder.contains(item)) {
                globalQuestsInGameOrder.add(item)
            }
        }
        enemyQuests.forEach {
            val item = Items.index(it)
            if (!globalQuestsInGameOrder.contains(item)) {
                globalQuestsInGameOrder.add(item)
            }
        }

        if (globalQuestsInGameOrder.size == 11) {
            for (item in Items.items) {
                val idx = Items.index(item)
                if (!globalQuestsInGameOrder.contains(idx)) {
                    globalQuestsInGameOrder.add(idx)
                }
            }
        }
    }

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

    return InputConditions(turnType, gameBoard, ourQuestsSet, enemyQuestsSet, we, enemy)
}

data class InputConditions(
    val turnType: Int,
    val gameBoard: GameBoard,
    val ourQuests: Int,
    val enemyQuests: Int,
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
    companion object {
        val realAllPushes = (0..6).flatMap { ourRowColumn ->
            Direction.allDirections
                .flatMap { ourDirection ->
                    (0..6).flatMap { enemyRowColumn ->
                        Direction.allDirections.map { enemyDirection ->
                            Pushes(
                                onePush(ourDirection, ourRowColumn),
                                onePush(enemyDirection, enemyRowColumn)
                            )
                        }
                    }
                }
        }

        var allPushes: List<Pushes> = (0..0).flatMap { ourRowColumn ->
            Direction.allDirections
                .flatMap { ourDirection ->
                    (0..0).flatMap { enemyRowColumn ->
                        Direction.allDirections.map { enemyDirection ->
                            Pushes(
                                onePush(ourDirection, ourRowColumn),
                                onePush(enemyDirection, enemyRowColumn)
                            )
                        }
                    }
                }
        }

        fun installRealPushes() {
            allPushes = realAllPushes
        }
    }

    fun collision(): Boolean {
        return ourPush.collision(enemyPush)
    }
}

data class PushAndMove(
    val pushes: Pushes,
    val board: GameBoard,
    val ourPlayer: Player,
    val enemyPlayer: Player,
    val ourQuests: Int,
    val enemyQuests: Int
) {

    val ourDomain = board.findDomain(ourPlayer.point, ourQuests, enemyQuests)
    val enemyDomain = board.findDomain(enemyPlayer.point, ourQuests, enemyQuests)
    val ourFieldOnHand = board.bitBoard.ourField()
    val enemyFieldOnHand = board.bitBoard.enemyField()

    val enemySpace = enemyDomain.size
    val ourSpace = ourDomain.size
    val enemyQuestCompleted = enemyDomain.getEnemyQuestsCount()
    val ourQuestCompleted = ourDomain.getOurQuestsCount()

    val score: Double = this.let { push ->
        val ourItemRemain = push.ourPlayer.numPlayerCards - push.ourQuestCompleted
        val enemyItemRemain = push.enemyPlayer.numPlayerCards - push.enemyQuestCompleted
        if (ourItemRemain == 0) {
            if (enemyItemRemain == 0) {
                val enemyPushToLastQuest = push.enemyPlayer.numPlayerCards == 1 &&
                        push.board[push.enemyPlayer.point].item < 0
                val ourPushToLastQuest = push.ourPlayer.numPlayerCards == 1 &&
                        push.board[push.ourPlayer.point].item > 0
                if (enemyPushToLastQuest.xor(ourPushToLastQuest)) {
                    if (enemyPushToLastQuest) {
                        return@let 0.0
                    } else {
                        return@let 1.0
                    }
                }
                return@let 0.5
            } else {
                return@let 1.0
            }
        }
        if (enemyItemRemain == 0) {
            return@let 0.0
        }

        val secondaryScore = (space(push).toDouble() + itemOnHandScore(push) * 25) / (50 + 25)
        val gameEstimate = if (push.pushes.collision()) {
            if (numberOfDraws == 0) {
                computeEstimate(ourItemRemain, enemyItemRemain, Math.max(pushesRemain - 1, 0), secondaryScore)
            } else {
                computeEstimate(ourItemRemain, enemyItemRemain, 9 - numberOfDraws, secondaryScore)
            }
        } else {
            computeEstimate(ourItemRemain, enemyItemRemain, pushesRemain, secondaryScore)
        }

        return@let gameEstimate
    }
}

private fun findBestPush(
    we: Player,
    enemy: Player,
    gameBoard: GameBoard,
    ourQuests: Int,
    enemyQuests: Int,
    step: Int,
    prevPushesAtThisPosition: List<Pushes>? = null,
    numberOfDraws: Int = 0
): OnePush {

    val startTimeNanos = System.nanoTime()
    val timeLimitNanos = TimeUnit.MILLISECONDS.toNanos(if (step == 0) 500 else 40)
    val deadlineTimeNanos = timeLimitNanos + startTimeNanos

    val pushes = computePushes(
        gameBoard,
        we,
        ourQuests,
        emptyList(),
        enemy,
        enemyQuests
    )
    probablyLogCompilation()

//    val result = if (deadlineTimeNanos - System.nanoTime() < TimeUnit.MILLISECONDS.toNanos(10)) {
//    val result =     selectBestPushByTwoComparators(pushes)
//    } else {

    val weLose = we.numPlayerCards > enemy.numPlayerCards
    val weLoseOrDrawAtEarlyGame = weLose || (we.numPlayerCards == enemy.numPlayerCards && step < 50)
    val excludeOur = weLoseOrDrawAtEarlyGame && numberOfDraws > 3

    val result = if (prevPushesAtThisPosition != null && numberOfDraws > 0 && !excludeOur) {

        val ourBestPush = prevPushesAtThisPosition.last().ourPush

        val enemyBestResponse = pushes.minBy {
            if (it.pushes.ourPush != ourBestPush) {
                2.0 // bigger than any valid score
            } else {
                it.score
            }
        }!!

        val ourBestResponseOnEnemyResponse = pushes.maxBy {
            if (it.pushes.enemyPush != enemyBestResponse.pushes.enemyPush) {
                -2.0 // bigger than any valid score
            } else {
                it.score
            }
        }!!

        log(
            "we think that the best move is $ourBestPush, but enemy can respond with ${enemyBestResponse.pushes.enemyPush} with score=${enemyBestResponse.score}, " +
                    "collision=${enemyBestResponse.pushes.collision()}"
        )

        if (enemyBestResponse.pushes.collision()) {
            log("as collision is the best enemy response, lets proceed")
            ourBestPush
        } else {
            log("our best respond on enemy respond is ${ourBestResponseOnEnemyResponse.pushes.ourPush} with score=${ourBestResponseOnEnemyResponse.score};")

            val ourMoves = arrayOf(ourBestPush, ourBestResponseOnEnemyResponse.pushes.ourPush)
            val enemyMoves = arrayOf(enemyBestResponse.pushes.enemyPush, ourBestPush, ourBestPush.opposite)

            for ((ourIdx, ourMove) in ourMoves.withIndex()) {
                for ((enemyIdx, enemyMove) in enemyMoves.withIndex()) {
                    a[enemyIdx][ourIdx] =
                        pushes.single { it.pushes.ourPush == ourMove && it.pushes.enemyPush == enemyMove }.score
                }
            }

            val solution = solvePivot(a, enemyMoves.size, ourMoves.size)

            log(
                "our reduced strategy: ${
                    solution.ourStrategy.zip(ourMoves).sortedByDescending { it.first }
                        .map { "${it.second}=${twoDigitsAfterDotFormat.format(it.first)}" }
                }"
            )
            log(
                "enemy reduced strategy: ${
                    solution.enemyStrategy.zip(enemyMoves).sortedByDescending { it.first }
                        .map { "${it.second}=${twoDigitsAfterDotFormat.format(it.first)}" }
                }"
            )

            val (firstStrat, secondStrat) = solution.ourStrategy.zip(ourMoves).sortedByDescending { it.first }

            if (secondStrat.first > firstStrat.first * 0.8) {
                val selection = rand.nextDouble()

                if (selection < firstStrat.first) {
                    firstStrat.second
                } else {
                    secondStrat.second
                }
            } else {
                firstStrat.second
            }
        }
    } else {

        selectPivotSolver(we, enemy, pushes, step, numberOfDraws, prevPushesAtThisPosition)
    }
//    }
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

val a = Array(28) { DoubleArray(28) { 0.0 } } // interior[column][row]
val stringBuilder = StringBuilder(10_000)

//pivot method from https://www.math.ucla.edu/~tom/Game_Theory/mat.pdf
private fun selectPivotSolver(
    we: Player,
    enemy: Player,
    pushes: List<PushAndMove>,
    step: Int,
    numberOfDraws: Int,
    prevPushesAtThisPosition: List<Pushes>?
): OnePush {
    log("pivotSolver: prev pushes at this position: $prevPushesAtThisPosition")
    log("pivotSolver: enemy draw repetitions: $drawRepetitionsStr")
    log("pivotSolver: enemy nonDraw repetitions: $nonDrawRepetitionsStr")

    var threshold = 0.0000001
    fun r(value: Double): Double =
        if (value > -threshold && value < threshold) 0.0 else value

    val prevEnemyPushes = prevPushesAtThisPosition?.flatMap {
        if (it.collision()) {
            listOf(it.enemyPush, it.enemyPush.opposite)
        } else {
            listOf(it.enemyPush)
        }
    }
    val prevOurPushes = prevPushesAtThisPosition?.map { it.ourPush }

    val pushes = if (prevEnemyPushes == null || prevEnemyPushes.isEmpty()) {
        pushes
    } else {
        prevOurPushes!!
        val isDraw = numberOfDraws != 0
        val enemyType = if (isDraw) {
            if (drawRepetitions[numberOfDraws] == UNKNOWN) {
                val type = drawRepetitions[numberOfDraws - 1]
                log("I guess that enemy type is $type")
                type
            } else {
                val type = drawRepetitions[numberOfDraws]
                log("enemy type $type")
                type
            }
        } else {
            val type = nonDrawRepetitions[prevEnemyPushes.size]
            log("enemy type $type")
            type
        }
        val weLose = we.numPlayerCards > enemy.numPlayerCards
        val weLoseOrDrawAtEarlyGame = weLose || (we.numPlayerCards == enemy.numPlayerCards && step < 50)
        val excludeOur = weLose
                && (numberOfDraws > 2 || numberOfDraws == 0)
                && enemyType == SAME_MOVE
        if (excludeOur) {
            log("filter out our pushes: $prevOurPushes")
        }

        when (enemyType) {
            NOT_PREVIOUS_MOVE -> pushes.filterNot {
                val lastEnemyPush = prevEnemyPushes.last()
                it.pushes.enemyPush == lastEnemyPush
                        || (isDraw && it.pushes.enemyPush == lastEnemyPush.opposite)
            }
            SAME_MOVE -> pushes.filter {
                val lastEnemyPush = prevEnemyPushes.last()
                it.pushes.enemyPush == lastEnemyPush
                        || (isDraw && it.pushes.enemyPush == lastEnemyPush.opposite)
                        || (excludeOur && it.pushes.ourPush != prevOurPushes.last())
            }
            else -> pushes

        }
    }

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

    if (false) {
        stringBuilder.clear()

        stringBuilder.append("\n#our\\enemy | ")
        enemyPushes.joinTo(stringBuilder, " | ")
        stringBuilder.append("\n")
        for (j in (0 until OUR_SIZE)) {
            stringBuilder.append("#       ")
            stringBuilder.append(ourPushes[j])
            stringBuilder.append(" | ")
            for (i in (0 until ENEMY_SIZE)) {
                stringBuilder.append((100 * a[i][j]).toInt().toString().padStart(2))
                stringBuilder.append(" | ")
            }
            stringBuilder.append("\n")
        }
        log(stringBuilder.toString())
    }

    val result = solvePivot(a, ENEMY_SIZE, OUR_SIZE)

    val (ourStrategy, enemyStrategy, score) = result

    val selection = rand.nextDouble()

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
        val theBest = ourStrategy
            .mapIndexed { idx, score -> ourPushes[idx] to score }
            .sortedByDescending { it.second }
            .first()

        val best = ourStrategy
            .mapIndexed { idx, score -> ourPushes[idx] to score }
            .sortedByDescending { it.second }
            .filter { it.second > theBest.second * 0.8 }

        val norm = best.sumByDouble { it.second }
        var currentSum = 0.0
        for (idx in best.indices) {
            currentSum += best[idx].second
            if (currentSum >= selection * norm) {
                return best[idx].first
            }
        }
    }
    throw IllegalStateException("aaaaa")

}

private fun solvePivot(a: Array<DoubleArray>, ENEMY_SIZE: Int, OUR_SIZE: Int): PivotSolverResult {
    var threshold = 0.0000001
    fun r(value: Double): Double =
        if (value > -threshold && value < threshold) 0.0 else value

    val hLabel = IntArray(ENEMY_SIZE) { idx -> -idx - 1 } // y_i are represented by negative ints
    val vLabel = IntArray(OUR_SIZE) { idx -> idx + 1 } // x_i are represented by  positives ints

    val bottom = DoubleArray(ENEMY_SIZE) { idx -> -1.0 }
    val right = DoubleArray(OUR_SIZE) { idx -> 1.0 }

    var corner: Double = 0.0
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

    log("resultScore = $resultScore, duration = ${TimeUnit.NANOSECONDS.toMillis(duration)}, pivots = $pivotCount")

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

    val result = PivotSolverResult(ourStrategy, enemyStrategy, resultScore)
    return result
}

data class PivotSolverResult(val ourStrategy: DoubleArray, val enemyStrategy: DoubleArray, val score: Double)

fun computePushes(
    gameBoard: GameBoard,
    we: Player,
    ourQuests: Int,
    forbiddenPushMoves: List<OnePush> = emptyList(),
    enemy: Player,
    enemyQuests: Int
): List<PushAndMove> {
    val result = mutableListOf<PushAndMove>()
    for (pushes in Pushes.allPushes) {
        if (forbiddenPushMoves.contains(pushes.ourPush)) {
            continue
        }
        val ourPlayer = we.push(pushes)
        val enemyPlayer = enemy.push(pushes)
        val newBoard = gameBoard.push(pushes)
        val pushAndMove = PushAndMove(
            pushes = pushes,
            board = newBoard,
            ourPlayer = ourPlayer,
            enemyPlayer = enemyPlayer,
            ourQuests = ourQuests,
            enemyQuests = enemyQuests
        )
        result.add(pushAndMove)
    }

    return result
}

object PushSelectors {

    fun itemOnHandScore(push: PushAndMove): Int {
        val ourScore = if (push.ourDomain.hasAccessToBorder && push.ourFieldOnHand.ourQuestItem(
                push.ourPlayer.playerId,
                push.ourQuests
            )
        ) {
            1
        } else {
            0
        }
        val enemyScore = if (push.enemyDomain.hasAccessToBorder && push.enemyFieldOnHand.ourQuestItem(
                push.enemyPlayer.playerId,
                push.enemyQuests
            )
        ) {
            1
        } else {
            0
        }

        return ourScore - enemyScore
    }

    fun space(push: PushAndMove): Int {
        return push.ourSpace - push.enemySpace
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
        val NO_ITEM: Long = 12.shl(4)
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
    val maxX: Int,
    val minX: Int,
    val maxY: Int,
    val minY: Int
) {

    val size: Int = java.lang.Long.bitCount(domainBits)
    val hasAccessToBorder = domainBits.and(BORDER) != 0L

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

    fun getOurQuestsCount(): Int {
        return Integer.bitCount(ourQuestBits)
    }

    fun getEnemyQuestsCount(): Int {
        return Integer.bitCount(enemyQuestBits)
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
    private val cachedPaths = arrayOfNulls<MutableList<PathElem>>(2)
    val domains = Domains()
    private var parent: GameBoard? = null
    private var pushFromParent: Pushes? = null

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
            val cachedValue = parent!!.domains.get(point, ourQuestsSet, enemyQuestsSet)
            if (cachedValue != null) {
                val pushes = pushFromParent!!
                val affectOurX =
                    pushes.ourPush.direction.isVertical && cachedValue.maxX + 1 >= pushes.ourPush.rowColumn && cachedValue.minX - 1 <= pushes.ourPush.rowColumn
                val affectOurY =
                    !pushes.ourPush.direction.isVertical && cachedValue.maxY + 1 >= pushes.ourPush.rowColumn && cachedValue.minY - 1 <= pushes.ourPush.rowColumn
                val affectEnemyX =
                    pushes.enemyPush.direction.isVertical && cachedValue.maxX + 1 >= pushes.enemyPush.rowColumn && cachedValue.minX - 1 <= pushes.enemyPush.rowColumn
                val affectEnemyY =
                    !pushes.enemyPush.direction.isVertical && cachedValue.maxY + 1 >= pushes.enemyPush.rowColumn && cachedValue.minY - 1 <= pushes.enemyPush.rowColumn
                if (!(affectEnemyX || affectEnemyY || affectOurX || affectOurY)) {
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
        while (true) {
            val nextPoint = readNextFront() ?: break
            maxX = max(maxX, nextPoint.x)
            minX = min(minX, nextPoint.x)
            maxY = max(maxY, nextPoint.y)
            minY = min(minY, nextPoint.y)
            val bitField = bitBoard[nextPoint]
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
            maxX = maxX,
            maxY = maxY,
            minX = minX,
            minY = minY
        )
        domains.set(domain)

        return domain
    }

    operator fun get(point: Point) = bitBoard[point]

    fun findPaths(player: Player, quests: Int): List<PathElem> {
        if (cachedPaths[player.playerId] == null) {
            fun coordInVisited(newPoint: Point, newItemsSet: Int): Int {
                val x = newPoint.x
                val y = newPoint.y
                var firstItem: Int = 0
                var secondItem: Int = 0
                var thirdItem: Int = 0
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
                        val item = bitBoard[newPoint].item
                        val newItems = if (item > 0 && quests[item]) {
                            pathElem.itemsTakenSet.set(item)
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

            cachedPaths[player.playerId] = result
        }
        return cachedPaths[player.playerId]!!
    }

    fun push(pushes: Pushes): GameBoard {
        if (pushes.collision()) {
            return this
        }
        val firstPush = if (pushes.ourPush.direction.isVertical) {
            pushes.enemyPush
        } else {
            pushes.ourPush
        }
        val secondPush = if (pushes.ourPush.direction.isVertical) {
            pushes.ourPush
        } else {
            pushes.enemyPush
        }
        val firstPushIsEnemy = pushes.ourPush.direction.isVertical

        val rows = bitBoard.rows.clone()
        val hands = bitBoard.hands.clone()
        firstPush.run {
            val isEnemy = firstPushIsEnemy
            val playerId = if (isEnemy) 1 else 0
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
        secondPush.run {
            val isEnemy = !firstPushIsEnemy
            val playerId = if (isEnemy) 1 else 0
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

        return GameBoard(BitBoard(rows, hands)).apply {
            parent = this@GameBoard
            pushFromParent = pushes
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
    val isBorder: Boolean = (x == 0) || (y == 0) || (x == 6) || (y == 6)
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


    companion object {
        val points = (0..6).map { x ->
            (0..6).map { y ->
                Point(x, y)
            }
        }

        private val point_minus2 = Point(-2, -2)
        private val point_minus1 = Point(-1, -1)

        init {
            (0..6).forEach { x ->
                (0..6).forEach { y ->
                    if (x > 0) {
                        points[x][y].left = points[x - 1][y]
                    }
                    if (x < 6) {
                        points[x][y].right = points[x + 1][y]
                    }
                    if (y > 0) {
                        points[x][y].up = points[x][y - 1]
                    }
                    if (y < 6) {
                        points[x][y].down = points[x][y + 1]
                    }
                }
            }
        }

        fun point(x: Int, y: Int): Point {
            return when {
                x >= 0 -> points[x][y]
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

data class Player(
    val playerId: Int,
    val numPlayerCards: Int,
    val playerX: Int,
    val playerY: Int
) {

    constructor(playerId: Int, input: Scanner) : this(
        playerId = playerId,
        numPlayerCards = input.nextInt(), // the total number of quests for a player (hidden and revealed)
        playerX = input.nextInt(),
        playerY = input.nextInt()
    )

    val point: Point = Point.point(playerX, playerY)

    fun push(pushes: Pushes): Player {
        if (pushes.collision()) {
            return this
        }
        var x = playerX
        var y = playerY
        val firstPush = if (pushes.ourPush.direction.isVertical) {
            pushes.enemyPush
        } else {
            pushes.ourPush
        }
        val secondPush = if (pushes.ourPush.direction.isVertical) {
            pushes.ourPush
        } else {
            pushes.enemyPush
        }

        firstPush.run {
            if (direction.isVertical) {
                if (x == rowColumn) {
                    y = (y + (if (direction == UP) -1 else 1) + 7) % 7
                }
            } else {
                if (y == rowColumn) {
                    x = (x + (if (direction == LEFT) -1 else 1) + 7) % 7
                }
            }
        }

        secondPush.run {
            if (direction.isVertical) {
                if (x == rowColumn) {
                    y = (y + (if (direction == UP) -1 else 1) + 7) % 7
                }
            } else {
                if (y == rowColumn) {
                    x = (x + (if (direction == LEFT) -1 else 1) + 7) % 7
                }
            }
        }

        return if (x != playerX || y != playerY) {
            copy(playerX = x, playerY = y)
        } else {
            this
        }
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

class Quest(input: Scanner) {
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
            toPush.gameBoard,
            toPush.ourQuests,
            toPush.enemyQuests,
            step = 0 // big limit
        )
        lastBoard = toPush.gameBoard
        lastBoardAndElves = BoardAndElves(toPush.gameBoard, toPush.we.point, toPush.enemy.point)
        log("warmup once move")
        findBestMove(
            gameBoard = toMove.gameBoard,
            allBoards = mutableMapOf(),
            we = toMove.we,
            ourQuests = toMove.ourQuests,
            step = 1,
            enemy = toMove.enemy,
            enemyQuests = toMove.enemyQuests
        )
    }

    fun warmup(limitNanos: Long) {
        val start = System.nanoTime()

        while (true) {
            if (System.nanoTime() > start + limitNanos) {
                break
            }
            findBestPush(
                toPush.we,
                toPush.enemy,
                toPush.gameBoard,
                toPush.ourQuests,
                toPush.enemyQuests,
                step = 2 // small limit
            )
            if (System.nanoTime() > start + limitNanos) {
                break
            }
            findBestMove(
                gameBoard = toMove.gameBoard,
                allBoards = mutableMapOf(),
                we = toMove.we,
                ourQuests = toMove.ourQuests,
                step = 1,
                enemy = toMove.enemy,
                enemyQuests = toMove.enemyQuests
            )
        }
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

    fun indexesToNames(set: Int): List<String> {
        val result = mutableListOf<String>()
        for (itemName in items) {
            if (set[index(itemName)]) {
                result.add(itemName)
            }
        }
        return result
    }

    val NO_ITEM = 0
}

private val fingerprints: Map<Fingerprint, DoubleArray> = mapOf()
