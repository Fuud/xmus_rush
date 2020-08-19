@file:Suppress("NAME_SHADOWING", "UnnecessaryVariable")

import Direction.*
import Item.Companion.isBelongToQuest
import Item.Companion.questMask
import PushSelectors.itemsCountDiff
import PushSelectors.pushOutItems
import PushSelectors.space
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
import kotlin.collections.ArrayList
import kotlin.collections.component1
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.measureNanoTime

/**
 * Help the Christmas elves fetch presents in a magical labyrinth!
 **/
val startTime = System.currentTimeMillis()
fun log(s: Any?) {
    System.err.println("\n#[${System.currentTimeMillis() - startTime}] $s\n")
}


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

enum class EnemyType {
    UNKNOWN,

    STABLE,

    UNSTABLE
}

var enemyType = EnemyType.UNKNOWN

private fun initProbabilities() {
    val p00 = 0.611
    val p01 = 0.151
    val p02 = 0.018
    val p10 = 0.151
    val p11 = 0.040
    val p12 = 0.05
    val p20 = 0.018
    val p21 = 0.05
    val p22 = 0.01
    val oe = doubleArrayOf(p00, p01, p02, p10, p11, p12, p20, p21, p22)
    val duration = measureNanoTime {
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
        if (compilationTime > lastCompilationTime){
            log("Compilation: diff: ${compilationTime - lastCompilationTime} total: $compilationTime")
            lastCompilationTime = compilationTime
        }
    }
}

object BoardCache {
    private val boards = mutableListOf<Array<Field>>()
    private var index = 0

    fun newBoard(orig: Array<Field>): Array<Field> {
        return if (index == boards.size) {
            val result = orig.clone()
            boards.add(result)
            index++
            result
        } else {
            val result = boards[index]
            System.arraycopy(orig, 0, result, 0, orig.size)
            index++
            result
        }
    }

    fun reset() {
        index = 0
    }
}

val rand = Random(777)

val moveDomains = Domains()
val moveScores = Point.points.flatten()
    .map { it to 0 }
    .toMap().toMutableMap()


var lastBoard: GameBoard? = null
var lastPush: OnePush? = null
var numberOfDraws = 0

fun performGame() {
    val globalStart = System.nanoTime()
    initProbabilities()
    Warmup.warmupOnce()
    Pushes.installRealPushes()

    try {
//        val input = Scanner(System.`in`)
        val input = Scanner(TeeInputStream(System.`in`, System.err))

//        val input = Scanner(
//            StringReader(
//                """""".trimIndent()
//            )
//        )

        // game loop

        val allBoards = mutableMapOf<GameBoard, MutableSet<Pushes>>()

        repeat(150) { step ->
            val start = if (step == 0) globalStart else System.nanoTime()
            log("step $step")
            BoardCache.reset()

            val (turnType, gameBoard, ourQuests, enemyQuests, we, enemy) = readInput(input)

            // Write an action using println()
            // To debug: System.err.println("Debug messages...");
            if (turnType == 0) {
                val duration = measureNanoTime {
                    val prevMovesAtThisPosition = allBoards[gameBoard]

                    log("consecutiveDraws=$numberOfDraws duplicate=${prevMovesAtThisPosition != null}")

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
                    lastPush = bestMove

                    if (step == 0) {
                        Warmup.warmup(start + TimeUnit.MILLISECONDS.toNanos(500) - System.nanoTime())
                    }

                    while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(30)) {
                        log("time to sleep")
                        Thread.sleep(10)
                    }

                    println("PUSH ${bestMove.rowColumn} ${bestMove.direction}")

                }

                log("Duration: ${TimeUnit.NANOSECONDS.toMillis(duration)}")
//                }
            } else {
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

                    if (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(30)) {
                        log("time to sleep")
                        Thread.sleep(10)
                    }

                    if (bestPath != null) {
                        val directions = mutableListOf<Direction>()
                        var pathElem: PathElem = bestPath
                        while (pathElem.prev != null) {
                            val direction = pathElem.direction!!
                            directions.add(0, direction)
                            pathElem = pathElem.prev!!
                        }
                        if (directions.isEmpty()) {
                            println("PASS")
                        } else {
                            println("MOVE " + directions.joinToString(separator = " "))
                        }
                    } else {
                        println("PASS")
                    }
                }
                log("Duration: ${TimeUnit.NANOSECONDS.toMillis(duration)}")
            }
        }
    } catch (t: Throwable) {
        t.printStackTrace()
        throw t
    }
}

private fun findBestMove(
    gameBoard: GameBoard,
    allBoards: MutableMap<GameBoard, MutableSet<Pushes>>,
    we: Player,
    ourQuests: List<String>,
    step: Int,
    enemy: Player,
    enemyQuests: List<String>
): PathElem? {
    log("findBestMove")
    val startTime = System.nanoTime()
    val lastPush = lastPush!!
    val lastBoard = lastBoard!!
    val wasDrawAtPrevMove = lastBoard == gameBoard
    if (wasDrawAtPrevMove) {
        numberOfDraws++
    } else {
        numberOfDraws = 0
    }
    if (!wasDrawAtPrevMove) {
        val enemyLastPush = tryFindEnemyPush(lastBoard, gameBoard, lastPush)
        if (enemyLastPush != null) {
            //todo add elves position to gameBoard
            allBoards.putIfAbsent(lastBoard, mutableSetOf())
            val enemyMovesInThisPosBeforeLastMove = allBoards[lastBoard]!!.map { it.enemyPush }
            allBoards[lastBoard]!!.add(Pushes(lastPush, enemyLastPush))
            if (enemyType == EnemyType.UNKNOWN || enemyType == EnemyType.STABLE) {
                if (enemyMovesInThisPosBeforeLastMove.isEmpty()) {
                    // do nothing
                } else if (enemyLastPush in enemyMovesInThisPosBeforeLastMove) {
                    enemyType = EnemyType.STABLE
                } else {
                    enemyType = EnemyType.UNSTABLE
                }
            }
        }
    } else {
        allBoards.putIfAbsent(lastBoard, mutableSetOf())
        val enemyMovesInThisPosBeforeLastMove = allBoards[lastBoard]!!.map { it.enemyPush }
        val previousPushes = allBoards[lastBoard]!!
        if (previousPushes.none { it.ourPush == lastPush }) {
            previousPushes.add(Pushes(lastPush, lastPush))
            previousPushes.add(
                Pushes(lastPush, lastPush.copy(direction = lastPush.direction.opposite))
            )
        }
        if (enemyType == EnemyType.UNKNOWN || enemyType == EnemyType.STABLE) {
            if (enemyMovesInThisPosBeforeLastMove.isNotEmpty() && previousPushes.size == 2) {
                enemyType = EnemyType.STABLE
            } else {
                enemyType = EnemyType.UNSTABLE
            }
        }
    }


    val paths = gameBoard.findPaths(we, ourQuests)
    //todo there are rare mazes where we can complete any two quests from three
    val itemsTaken = paths.maxWith(compareBy { it.itemsTaken.size })!!.itemsTaken
    val itemsTakenSize = itemsTaken.size
    val ourNextQuests = ourQuests.toMutableList()
    ourNextQuests.removeAll(itemsTaken)
    val ends = paths.filter { it.itemsTaken.size == itemsTakenSize }
        .map { it.point }.toHashSet()
    ends.forEach { moveScores[it] = 0 }

    val timeLimit = TimeUnit.MILLISECONDS.toNanos(if (step == 0) 500 else 40)

    val possibleQuestCoef = if (we.numPlayerCards - ourQuests.size > 0) {
        itemsTakenSize * 1.0 / (we.numPlayerCards - ourQuests.size)
    } else {
        0.0
    }
    var count = 0
    for (pushes in Pushes.allPushes) {
        if (System.nanoTime() - startTime > timeLimit && count > 0) {
            log("stop computePushes, computed $count pushes")
            break
        }
        count++

        val pushAndMove = pushAndMove(
            gameBoard,
            pushes,
            we,
            ourNextQuests,
            enemy,
            enemyQuests
        )
        if (pushAndMove.board === gameBoard) {
            continue
        }
        moveDomains.clear()
        ends.forEach { point ->
            var fake = Player(-1, -1, point.x, point.y)
            val pushP = if (pushes.ourPush.direction.isVertical) {
                fake =
                    fake.push(pushes.enemyPush.direction, pushes.enemyPush.rowColumn)
                fake = fake.push(pushes.ourPush.direction, pushes.ourPush.rowColumn)
                fake.point
            } else {
                fake = fake.push(pushes.ourPush.direction, pushes.ourPush.rowColumn)
                fake =
                    fake.push(pushes.enemyPush.direction, pushes.enemyPush.rowColumn)
                fake.point
            }
            val domain =
                pushAndMove.board.findDomain(pushP, ourNextQuests, enemyQuests, moveDomains, itemsTaken)
            val score =
                ((domain.getOurQuestsCount() * 16 + 12 * domain.ourItems * possibleQuestCoef) * 4 + domain.size).toInt()
            moveScores[point] = moveScores[point]!! + score
        }
    }

    probablyLogCompilation()

    val pathsComparator = compareBy<PathElem> { pathElem ->
        pathElem.itemsTaken.size
    }.thenComparing { pathElem ->
        moveScores[pathElem.point]!!
    }.thenComparing { pathElem ->
        max(2 * abs(pathElem.point.x - 3) + 1, 2 * abs(pathElem.point.y - 3))
    }.thenComparing { pathElem ->
        val ourField = gameBoard.ourField
        if (ourField.containsQuestItem(we.playerId, ourQuests)) {
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
        gameBoard[pathElem.point].tile.roads
    }

    val bestPath = paths.maxWith(pathsComparator)
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

            val enemyPush = OnePush(enemyDirection, enemyRowColumn)
            val sortedActions = listOf(ourPush, enemyPush).sortedByDescending { it.direction.priority }

            var newBoard = fromBoard
            sortedActions.forEach { push ->
                val pushPlayer = if (push === enemyPush) {
                    1
                } else {
                    0
                }
                val board = newBoard.push(pushPlayer, push.direction, push.rowColumn)
                newBoard = board
            }
            if (newBoard == toBoard) {
                return enemyPush
            }
        }
    }
    return null // for example if item was taken immediately after push we cannot find enemy move
}

private fun readInput(input: Scanner): InputConditions {
    val turnType = input.nextInt() // 0 - push, 1 - move
    val board = BoardDto(input)

    val we = Player(0, input)
    val ourTile = Tile.read(input)

    val enemy = Player(1, input)
    val enemyTile = Tile.read(input)

    val items = (0 until input.nextInt()).map { ItemDto(input) }
    val quests = (0 until input.nextInt()).map { Quest(input) }

    val ourField = Field(ourTile, item = items.singleOrNull { it.isOnOurHand }?.toItem())
    val enemyField = Field(enemyTile, item = items.singleOrNull { it.isOnEnemyHand }?.toItem())

    val ourQuests = quests.filter { it.questPlayerId == 0 }.map { it.questItemName }
    val enemyQuests = quests.filter { it.questPlayerId == 1 }.map { it.questItemName }

    val boardArray = Array(7 * 7) { idx ->
        val x = idx % 7
        val y = idx / 7
        Field(
            board.board[y][x],
            items.singleOrNull { it.itemX == x && it.itemY == y }?.toItem()
        )
    }
    val gameBoard = GameBoard(
        boardArray,
        ourField,
        enemyField
    )

    return InputConditions(turnType, gameBoard, ourQuests, enemyQuests, we, enemy)
}

data class InputConditions(
    val turnType: Int,
    val gameBoard: GameBoard,
    val ourQuests: List<String>,
    val enemyQuests: List<String>,
    var we: Player,
    var enemy: Player
)

data class OnePush(val direction: Direction, val rowColumn: Int) {
    val idx = direction.ordinal * 7 + rowColumn

    companion object {
        val allPushes = Direction.allDirections.flatMap { dir -> (0..6).map { OnePush(dir, it) } }

        fun byIdx(idx: Int) = allPushes[idx]
    }

    override fun toString(): String {
        return "${direction.name.first()}$rowColumn"
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
                                OnePush(ourDirection, ourRowColumn),
                                OnePush(enemyDirection, enemyRowColumn)
                            )
                        }
                    }
                }
        }.shuffled(rand)

        var allPushes: List<Pushes> = (0..0).flatMap { ourRowColumn ->
            Direction.allDirections
                .flatMap { ourDirection ->
                    (0..0).flatMap { enemyRowColumn ->
                        Direction.allDirections.map { enemyDirection ->
                            Pushes(
                                OnePush(ourDirection, ourRowColumn),
                                OnePush(enemyDirection, enemyRowColumn)
                            )
                        }
                    }
                }
        }.shuffled(rand)

        fun installRealPushes(){
            allPushes = realAllPushes
        }

    }

    fun collision(): Boolean {
        return ourPush.idx == enemyPush.idx && (ourPush.direction.isVertical == enemyPush.direction.isVertical)
    }
}

data class PushAndMove(
    val pushes: Pushes,
    val board: GameBoard,
    val ourPlayer: Player,
    val enemyPlayer: Player,
    val ourQuests: List<String>,
    val enemyQuests: List<String>
) {

    companion object {
        private val pooledDomains = Domains()
    }

    init {
        pooledDomains.clear()
    }

    private val ourDomain = board.findDomain(ourPlayer.point, ourQuests, enemyQuests, pooledDomains, emptyList())
    private val enemyDomain = board.findDomain(enemyPlayer.point, ourQuests, enemyQuests, pooledDomains, emptyList())
    val ourFieldOnHand = board.ourField
    val enemyFieldOnHand = board.enemyField

    val enemySpace = enemyDomain.size
    val ourSpace = ourDomain.size
    val enemyQuestCompleted = enemyDomain.getEnemyQuestsCount()
    val ourQuestCompleted = ourDomain.getOurQuestsCount()
}

data class Action(val push: OnePush, val isEnemy: Boolean)

private fun findBestPush(
    we: Player,
    enemy: Player,
    gameBoard: GameBoard,
    ourQuests: List<String>,
    enemyQuests: List<String>,
    step: Int,
    prevPushesAtThisPosition: Set<Pushes>? = null,
    numberOfDraws: Int = 0
): OnePush {

    val startTimeNanos = System.nanoTime()
    val timeLimitNanos = TimeUnit.MILLISECONDS.toNanos(if (step == 0) 500 else 40)
    val deadlineTimeNanos = timeLimitNanos + startTimeNanos

//    val weLoseOrDrawAtEarlyGame = (we.numPlayerCards > enemy.numPlayerCards
//            || (we.numPlayerCards == enemy.numPlayerCards && gameBoard.step < 50))
//
//    val weHaveSeenThisPositionBefore = prevPushesAtThisPosition != null
//    val forbiddenPushMoves = if (weHaveSeenThisPositionBefore && weLoseOrDrawAtEarlyGame) {
//        //let's forbid random one push
//        val ourPushInSamePosition = prevPushesAtThisPosition!![rand.nextInt(prevPushesAtThisPosition.size)].ourPush
//        listOf(
//            ourPushInSamePosition,
//            ourPushInSamePosition.copy(direction = ourPushInSamePosition.direction.opposite)
//        )
//    } else {
//        emptyList()
//    }

    val pushes = computePushes(
        gameBoard,
        we,
        ourQuests,
        emptyList(),
        enemy,
        enemyQuests,
        timeLimitNanos = timeLimitNanos
    )
    probablyLogCompilation()

    val result = if (deadlineTimeNanos - System.nanoTime() < TimeUnit.MILLISECONDS.toNanos(10)) {
        selectBestPushByTwoComparators(pushes)
    } else {
        selectPivotSolver(we, enemy, gameBoard, pushes, step, numberOfDraws, prevPushesAtThisPosition)
    }
    probablyLogCompilation()
    return result
}

val a = Array<DoubleArray>(28) { DoubleArray(28) { 0.0 } } // interior[column][row]
val stringBuilder = StringBuilder(10_000)

//pivot method from https://www.math.ucla.edu/~tom/Game_Theory/mat.pdf
private fun selectPivotSolver(
    we: Player,
    enemy: Player,
    gameBoard: GameBoard,
    pushes: List<PushAndMove>,
    step: Int,
    numberOfDraws: Int,
    prevPushesAtThisPosition: Set<Pushes>?
): OnePush {
    log("pivotSolver: prev pushes at this position: $prevPushesAtThisPosition")

    val weLoseOrDrawAtEarlyGame = (we.numPlayerCards > enemy.numPlayerCards
            || (we.numPlayerCards == enemy.numPlayerCards && step < 50))

    val pushRemain = (150 - step) / 2 - 1
    val prevEnemyPushes = prevPushesAtThisPosition?.map { it.enemyPush }
    val prevOurPushes = prevPushesAtThisPosition?.map { it.ourPush }

    val pushes = if (prevEnemyPushes == null) {
        pushes
    } else {
        prevEnemyPushes!!
        prevOurPushes!!
        if (enemyType == EnemyType.UNSTABLE) {
            if (weLoseOrDrawAtEarlyGame && numberOfDraws > 2) {
                log("filter out our pushes: $prevOurPushes")
                pushes.filter { it.pushes.ourPush !in prevOurPushes }
            } else {
                pushes
            }
        } else {
            if (enemyType == EnemyType.STABLE && weLoseOrDrawAtEarlyGame) {
                log("filter out our pushes: $prevOurPushes and enemy pushes: $prevEnemyPushes")
                pushes.filter { it.pushes.enemyPush in prevEnemyPushes && it.pushes.ourPush !in prevOurPushes }
            } else {
                log("filter out enemy pushes: $prevEnemyPushes")
                pushes.filter { it.pushes.enemyPush in prevEnemyPushes }
            }
        }
    }

    fun score(push: PushAndMove): Double {
        val ourItemRemain = push.ourPlayer.numPlayerCards - push.ourQuestCompleted
        val enemyItemRemain = push.enemyPlayer.numPlayerCards - push.enemyQuestCompleted
        if (ourItemRemain == 0) {
            if (enemyItemRemain == 0) {
                val enemyPushToLastQuest = push.enemyPlayer.numPlayerCards == 1 &&
                        push.board.get(push.enemyPlayer.point).item?.itemPlayerId ?: -1 == 1
                val ourPushToLastQuest = push.ourPlayer.numPlayerCards == 1 &&
                        push.board.get(push.ourPlayer.point).item?.itemPlayerId ?: -1 == 1
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

        val gameEstimate = estimate[pushRemain][ourItemRemain][enemyItemRemain]
        if (pushRemain == 0) {
            return gameEstimate
        }

        val secondaryScore = pushOutItems(push) * 100 + space(push)
        val result = if (secondaryScore > 0) {
            var delta = estimate[pushRemain][ourItemRemain - 1][enemyItemRemain] - gameEstimate
            if (delta < 0) {
                log("!!!Negative delta=$delta for positive secondary: [$pushRemain][$ourItemRemain][$enemyItemRemain] ")
                delta = 0.0
            }
            gameEstimate + delta * 0.5 * secondaryScore / (34 * 100 + 48)
        } else if (secondaryScore < 0) {
            var delta = gameEstimate - estimate[pushRemain][ourItemRemain][enemyItemRemain - 1]
            if (delta < 0) {
                log("!!!Negative delta=$delta for negative secondary: [$pushRemain][$ourItemRemain][$enemyItemRemain] ")
                delta = 0.0
            }
            gameEstimate + delta * 0.5 * secondaryScore / (34 * 100 + 48)
        } else {
            gameEstimate
        }
        //todo use estimate[10 -numberOfDraws] instead of pow?
        if (numberOfDraws > 0 && push.pushes.collision()) {
            if (enemyItemRemain < ourItemRemain) {
                return Math.pow(result, numberOfDraws.toDouble() + 1)
            } else if (enemyItemRemain == ourItemRemain && step < 50 && numberOfDraws > 1) {
                return Math.pow(result, numberOfDraws.toDouble())
            }
        }

        return result
    }

    val ourPushes = pushes.groupBy { it.pushes.ourPush }.keys.toList()
    val OUR_SIZE = ourPushes.size
    val enemyPushes = pushes.groupBy { it.pushes.enemyPush }.keys.toList()
    val ENEMY_SIZE = enemyPushes.size

    for (push in pushes) {
        val score = score(push)
        a[enemyPushes.indexOf(push.pushes.enemyPush)][ourPushes.indexOf(push.pushes.ourPush)] = score
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


    val hLabel = IntArray(ENEMY_SIZE) { idx -> -idx - 1 } // y_i are represented by negative ints
    val vLabel = IntArray(OUR_SIZE) { idx -> idx + 1 } // x_i are represented by  positives ints

    val bottom = DoubleArray(ENEMY_SIZE) { idx -> -1.0 }
    val right = DoubleArray(OUR_SIZE) { idx -> 1.0 }

    var corner: Double = 0.0
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
                                val val_3c = right[j] / a[i][j]
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

                corner = corner - bottom[p] * right[q] / pivot

                for (i in (0 until ENEMY_SIZE)) {
                    if (i != p) {
                        bottom[i] = bottom[i] - bottom[p] * a[i][q] / pivot
                    }
                }
                bottom[p] = -bottom[p] / pivot

                for (j in (0 until OUR_SIZE)) {
                    if (j != q) {
                        right[j] = right[j] - a[p][j] * right[q] / pivot
                    }
                }
                right[q] = right[q] / pivot

                for (i in (0 until ENEMY_SIZE)) {
                    for (j in (0 until OUR_SIZE)) {
                        if (i != p && j != q) {
                            a[i][j] = a[i][j] - a[p][j] * a[i][q] / pivot
                        }
                    }
                }
                for (i in (0 until ENEMY_SIZE)) {
                    for (j in (0 until OUR_SIZE)) {
                        if (i != p && j == q) {
                            a[i][j] = a[i][j] / pivot
                        } else if (i == p && j != q) {
                            a[i][j] = -a[i][j] / pivot
                        } else if (i == p && j == q) {
                            a[i][j] = 1 / a[i][j]
                        }
                    }
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

    log("resultScore = $resultScore, duration = ${TimeUnit.NANOSECONDS.toMillis(duration)}")

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

    val selection = rand.nextDouble()

    log("OurStrategy: ${ourStrategy.mapIndexed { idx, score -> ourPushes[idx] to score }
        .sortedByDescending { it.second }.joinToString { "${it.first}=${twoDigitsAfterDotFormat.format(it.second)}" }}")
    log("EnemyStrategy: ${enemyStrategy.mapIndexed { idx, score -> enemyPushes[idx] to score }
        .sortedByDescending { it.second }.joinToString { "${it.first}=${twoDigitsAfterDotFormat.format(it.second)}" }}")

    log("selection=$selection ourSum=${ourStrategy.sum()} enemySum=${enemyStrategy.sum()}")

    run {
        val best = ourStrategy
            .mapIndexed { idx, score -> ourPushes[idx] to score }
            .sortedByDescending { it.second }
            .take(3)
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

private fun selectBestPushByTwoComparators(pushes: List<PushAndMove>): OnePush {
    log("twoComparators")
    val comparator =
        caching(PushSelectors.itemsCountDiffMin)
            .thenComparing(caching(PushSelectors.itemsCountDiffAvg))
            .thenComparing(caching(PushSelectors.pushOutItemsAvg))
            .thenComparing(caching(PushSelectors.spaceAvg))

    val enemyComparator =
        caching(PushSelectors.itemsCountDiffMax)
            .thenComparing(caching(PushSelectors.itemsCountDiffAvg))
            .thenComparing(caching(PushSelectors.pushOutItemsAvg))
            .thenComparing(caching(PushSelectors.spaceAvg))

    val enemyBestMoves = pushes
        .asSequence()
        .groupBy { it.pushes.enemyPush }
        .toList()
        .sortedWith(Comparator { left, right ->
            enemyComparator.compare(
                left.second,
                right.second
            )
        })
        .take(10)
        .map { it.first }
        .toList()

    enemyBestMoves.mapIndexed { index, onePush ->
        log("Expected enemy move #$index is $onePush")
    }

    val (bestMove) = pushes
        .filter { enemyBestMoves.contains(it.pushes.enemyPush) }
        .groupBy { it.pushes.ourPush }
        .maxWith(Comparator { left, right ->
            comparator.compare(
                left.value,
                right.value
            )
        })!!

//    println(PushResultTable(pushes))

    return bestMove
}


fun computePushes(
    gameBoard: GameBoard,
    we: Player,
    ourQuests: List<String>,
    forbiddenPushMoves: List<OnePush> = emptyList(),
    enemy: Player,
    enemyQuests: List<String>,
    timeLimitNanos: Long
): List<PushAndMove> {
    val startTime = System.nanoTime()

    val result = mutableListOf<PushAndMove>()
    for (pushes in Pushes.allPushes) {
        if (System.nanoTime() - startTime > timeLimitNanos) {
            log("stop computePushes, computed ${result.size} pushes")
            return result
        }
        if (forbiddenPushMoves.contains(pushes.ourPush)) {
            continue
        }
        val pushAndMove = pushAndMove(
            gameBoard,
            pushes,
            we,
            ourQuests,
            enemy,
            enemyQuests
        )
        result.add(pushAndMove)
    }

    return result
}

private fun pushAndMove(
    gameBoard: GameBoard,
    pushes: Pushes,
    we: Player,
    ourQuests: List<String>,
    enemy: Player,
    enemyQuests: List<String>
): PushAndMove {
    val enemyDirection = pushes.enemyPush.direction
    val enemyRowColumn = pushes.enemyPush.rowColumn
    val direction = pushes.ourPush.direction
    val rowColumn = pushes.ourPush.rowColumn
    val draw =
        enemyRowColumn == rowColumn && (direction == enemyDirection || direction == enemyDirection.opposite)

    val sortedActions = if (draw) {
        emptyList()
    } else {
        listOf(
            Action(OnePush(direction, rowColumn), isEnemy = false),
            Action(OnePush(enemyDirection, enemyRowColumn), isEnemy = true)
        ).sortedByDescending { it.push.direction.priority }
    }

    var newBoard = gameBoard
    var ourPlayer = we
    var enemyPlayer = enemy
    sortedActions.forEach { (push, isEnemy) ->
        val pushPlayer = if (isEnemy) enemyPlayer else ourPlayer
        val board = newBoard.push(pushPlayer.playerId, push.direction, push.rowColumn)
        newBoard = board
        ourPlayer = ourPlayer.push(
            push.direction,
            push.rowColumn
        )
        enemyPlayer = enemyPlayer.push(
            push.direction,
            push.rowColumn
        )
    }

    val pushAndMove = PushAndMove(
        pushes = pushes,
        board = newBoard,
        ourPlayer = ourPlayer,
        enemyPlayer = enemyPlayer,
        ourQuests = ourQuests,
        enemyQuests = enemyQuests
    )

//    comparePathsWithDomains(newBoard, ourPlayer, ourQuests, enemyPlayer, enemyQuests, pushAndMove)
    return pushAndMove
}

@Suppress("unused")
private fun comparePathsWithDomains(
    newBoard: GameBoard,
    ourPlayer: Player,
    ourQuests: List<String>,
    enemyPlayer: Player,
    enemyQuests: List<String>,
    pushAndMove: PushAndMove
) {

    val ourPaths: List<PathElem> = newBoard.findPaths(ourPlayer, ourQuests)
    val enemyPaths: List<PathElem> = newBoard.findPaths(enemyPlayer, enemyQuests)
    val enemySpace = enemyPaths.groupBy { it.point }.size
    val ourSpace = ourPaths.groupBy { it.point }.size
    val enemyQuestCompleted = enemyPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
    val ourQuestCompleted = ourPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
    if (pushAndMove.enemySpace != enemySpace
        || pushAndMove.ourSpace != ourSpace
        || pushAndMove.enemyQuestCompleted != enemyQuestCompleted
        || pushAndMove.ourQuestCompleted != ourQuestCompleted
    ) {
        System.err.println("!!!!")
    }

}

@Suppress("unused", "NestedLambdaShadowedImplicitParameter")
class PushResultTable(pushes: List<PushAndMove>) {
    data class PushResult(val itemsCountDiff: Int, val pushOutItems: Int, val space: Int)

    private fun calcPushResult(push: PushAndMove): PushResult {
        return PushResult(
            itemsCountDiff(push),
            pushOutItems(push),
            space(push)
        )
    }

    private val table = pushes
        .groupBy { it.pushes.ourPush }
        .mapValues {
            it.value.map { it.pushes.enemyPush to calcPushResult(it) }.toMap()
        }

    override fun toString(): String {
        val header =
            "our\\enemy |  " + Direction.allDirections.flatMap { dir ->
                    (0..6).map { rc ->
                        "${dir.name.padStart(
                            5
                        )}$rc"
                    }
                }
                .joinToString(separator = "    | ")
        val rows = Direction.allDirections.flatMap { dir ->
            (0..6).map { rc ->
                val header = "  ${dir.name.padStart(5)}$rc  | "
                val columns = Direction.allDirections.flatMap { enemyDir ->
                    (0..6).map { enemyRc ->
                        val pushResult = table[OnePush(dir, rc)]!![OnePush(enemyDir, enemyRc)]!!
                        val itemsCountDiff = pushResult.itemsCountDiff.toString().padStart(2)
                        val pushOutItems = pushResult.pushOutItems.toString().padStart(3)
                        val space = pushResult.space.toString().padStart(3)
                        "$itemsCountDiff,$pushOutItems,$space"
                    }
                }.joinToString(separator = " |")
                header + columns
            }
        }.joinToString(separator = "\n")
        return header + "\n" + rows
    }
}

@Suppress("UNCHECKED_CAST")
fun <U : Comparable<U>> caching(funct: (pushesAndMoves: List<PushAndMove>) -> Comparable<U>): Comparator<List<PushAndMove>> {
    val cache = IdentityHashMap<Any, Any>()
    return compareBy { pushesAndMoves: List<PushAndMove> ->
        cache.computeIfAbsent(pushesAndMoves) {
            funct(pushesAndMoves)
        } as Comparable<U>
    }
}

object PushSelectors {
    fun itemsCountDiff(push: PushAndMove): Int {
        return (push.ourQuestCompleted - push.enemyQuestCompleted)
    }

    private val itemCountDiff = { pushesAndMoves: List<PushAndMove> ->
        pushesAndMoves.map(this::itemsCountDiff)
    }

    val itemsCountDiffMax = { pushesAndMoves: List<PushAndMove> -> itemCountDiff(pushesAndMoves).max()!! }
    val itemsCountDiffMin = { pushesAndMoves: List<PushAndMove> -> itemCountDiff(pushesAndMoves).min()!! }
    val itemsCountDiffAvg = { pushesAndMoves: List<PushAndMove> -> itemCountDiff(pushesAndMoves).average() }

    fun pushOutItems(push: PushAndMove): Int {
        val ourScore = pushOutItems(push, push.ourPlayer.playerId, push.ourQuests)
        val enemyScore = pushOutItems(push, push.enemyPlayer.playerId, push.enemyQuests)
        return ourScore - enemyScore
    }

    private fun pushOutItems(push: PushAndMove, playerId: Int, quests: List<String>): Int {
        fun Field.holdOurQuestItem(playerId: Int, quests: List<String>): Boolean {
            return this.item != null && this.item.itemPlayerId == playerId && quests.contains(this.item.itemName)
        }

        val fieldOnHand = if (playerId == 0) {
            push.ourFieldOnHand
        } else {
            push.enemyFieldOnHand
        }
        val onHandScore =
            if (fieldOnHand.holdOurQuestItem(playerId, quests)) {
                16
            } else {
                0
            }

        var otherItemsScore = 0
        //todo 3 queries are better than 49 ones
        for (y in (0..6)) {
            for (x in (0..6)) {
                if (push.board.get(y, x).holdOurQuestItem(playerId, quests)) {
                    otherItemsScore += max(abs(3 - x), abs(3 - y)) * max(abs(3 - x), abs(3 - y))
                }
            }
        }

        return onHandScore + otherItemsScore
    }

    private val pushOutItems = { pushesAndMoves: List<PushAndMove> ->
        pushesAndMoves.map {
            pushOutItems(it)
        }
    }

    val pushOutItemsAvg = { pushesAndMoves: List<PushAndMove> -> pushOutItems(pushesAndMoves).average() }

    fun space(push: PushAndMove): Int {
        return push.ourSpace - push.enemySpace
    }

    val space = { pushesAndMoves: List<PushAndMove> ->
        pushesAndMoves.map { push ->
            push.ourSpace - push.enemySpace
        }
    }

    val spaceAvg = { pushesAndMoves: List<PushAndMove> -> space(pushesAndMoves).average() }
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
    val item: Item?
) {
    fun connect(field: Field, direction: Direction): Boolean {
        return this.tile.connect(field.tile, direction)
    }

    fun containsQuestItem(playerId: Int, quests: List<String>): Boolean {
        return this.item.isBelongToQuest(playerId, quests)
    }
}

data class DomainInfo(
    val size: Int,
    val ourQuestBits: Int,
    val enemyQuestBits: Int,
    val ourItems: Int,
    val enemyItems: Int
) {
    companion object {
        val empty = DomainInfo(0, 0, 0, 0, 0)
        val bits2Count: IntArray = intArrayOf(0, 1, 1, 2, 1, 2, 2, 3)
    }

    fun getOurQuestsCount(): Int {
        return bits2Count[ourQuestBits]
    }

    fun getEnemyQuestsCount(): Int {
        return bits2Count[enemyQuestBits]
    }
}

data class PathElem(
    val point: Point,
    val itemsTaken: Set<String>,
    var prev: PathElem?,
    var direction: Direction?
)

class Domains {
    private val domains: MutableList<MutableList<DomainInfo>> =
        (0..6).map { (0..6).map { DomainInfo.empty }.toMutableList() }.toMutableList()

    fun clear() {
        for (y in 0..6) {
            for (x in 0..6) {
                domains[y][x] = DomainInfo.empty
            }
        }
    }

    fun get(point: Point): DomainInfo {
        return domains[point]
    }

    fun set(domain: DomainInfo, x: Int, y: Int) {
        domains[y][x] = domain
    }

}

data class GameBoard(val board: Array<Field>, val ourField: Field, val enemyField: Field) {
    private val cachedPaths = arrayOfNulls<MutableList<PathElem>>(2)

    companion object {
        val pooledList1 = arrayListOf<PathElem>()
        val pooledList2 = arrayListOf<PathElem>()
        val pooledDomains: Array<IntArray> = Array(7) { IntArray(7) }
        val visitedPoints = BitSet(50)
        val pooledFront: MutableList<Point> = ArrayList(50)
    }

    fun get(y: Int, x: Int) = board[y * 7 + x]
    operator fun get(point: Point) = get(point.y, point.x)

    fun findDomain(
        point: Point,
        ourQuests: List<String>,
        enemyQuests: List<String>,
        domains: Domains,
        ourIgnoredItems: Collection<String>
    ): DomainInfo {
        if (domains.get(point).size > 0) {
            return domains.get(point)
        }
        for (y in 0..6) {
            for (x in 0..6) {
                pooledDomains[y][x] = -1
            }
        }
        visitedPoints.clear()

        var size = 0
        var ourQuestsBits = 0
        var enemyQuestsBits = 0
        var ourItem = 0
        var enemyItem = 0
        val domainId = 0

        visitedPoints.set(point.idx)
        pooledFront.clear()
        pooledFront.add(point)
        while (pooledFront.isNotEmpty()) {
            val nextPoint = pooledFront.removeAt(pooledFront.size - 1)
            size++
            val item = this[nextPoint].item
            pooledDomains[nextPoint] = domainId
            ourQuestsBits = ourQuestsBits.or(item.questMask(0, ourQuests))
            enemyQuestsBits = enemyQuestsBits.or(item.questMask(1, enemyQuests))
            ourItem += if (item != null && item.itemPlayerId == 0 && !ourIgnoredItems.contains(item.itemName)) 1 else 0
            enemyItem += if (item != null && item.itemPlayerId == 1) 1 else 0
            for (i in (0..3)) {
                val direction = Direction.allDirections[i]
                if (nextPoint.can(direction)) {
                    val newPoint = nextPoint.move(direction)
                    if (!visitedPoints.get(newPoint.idx)) {
                        visitedPoints.set(newPoint.idx)
                        pooledFront.add(newPoint)
                    }
                }
            }
        }
        val domain = DomainInfo(size, ourQuestsBits, enemyQuestsBits, ourItem, enemyItem)


        pooledDomains.forEachIndexed { y, row ->
            row.forEachIndexed { x, id ->
                if (id == domainId) {
                    domains.set(domain, x, y)
                }
            }
        }

        return domain
    }

    fun findPaths(player: Player, quests: List<String>): List<PathElem> {
        if (cachedPaths[player.playerId] == null) {
            fun coordInVisited(newPoint: Point, newItems: Collection<String>): Int {
                val x = newPoint.x
                val y = newPoint.y
                val firstItem = if (quests.isNotEmpty() && newItems.contains(quests[0])) 1 else 0
                val secondItem = if (quests.size > 1 && newItems.contains(quests[1])) 1 else 0
                val thirdItem = if (quests.size > 2 && newItems.contains(quests[2])) 1 else 0

                return (((x * 7 + y) * 2 + firstItem) * 2 + secondItem) * 2 + thirdItem
            }

            val initialItem = this[player.point].item
            val initial =
                if (initialItem.isBelongToQuest(player.playerId, quests)) {
                    PathElem(player.point, setOf(initialItem!!.itemName), null, null)
                } else {
                    PathElem(player.point, emptySet(), null, null)
                }


            pooledList1.clear()
            pooledList2.clear()
            var front = pooledList1
            front.add(initial)

            val result = mutableListOf<PathElem>()

            val visited = BitSet(7 * 7 * 8)
            visited.set(coordInVisited(initial.point, initial.itemsTaken))
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
                        val item = this[newPoint].item
                        val newItems = if (item.isBelongToQuest(player.playerId, quests)) {
                            pathElem.itemsTaken + item!!.itemName
                        } else {
                            pathElem.itemsTaken
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

    fun push(playerId: Int, direction: Direction, rowColumn: Int): GameBoard {
        val field = if (playerId == 0) ourField else enemyField
        val newBoard = BoardCache.newBoard(board)
        val newField: Field
        if (direction == LEFT || direction == RIGHT) {
            if (direction == LEFT) {
                newField = get(rowColumn, 0)
                newBoard[rowColumn * 7 + 6] = field
                for (x in (0..5)) {
                    newBoard[rowColumn * 7 + x] = get(rowColumn, x + 1)
                }
            } else {
                newField = get(rowColumn, 6)
                newBoard[rowColumn * 7 + 0] = field
                for (x in (1..6)) {
                    newBoard[rowColumn * 7 + x] = get(rowColumn, x - 1)
                }
            }
        } else {
            if (direction == UP) {
                newField = get(0, rowColumn)
                newBoard[6 * 7 + rowColumn] = field
                for (y in (0..5)) {
                    newBoard[y * 7 + rowColumn] = get(y + 1, rowColumn)
                }
            } else {
                newField = get(6, rowColumn)
                newBoard[0 * 7 + rowColumn] = field
                for (y in (1..6)) {
                    newBoard[y * 7 + rowColumn] = get(y - 1, rowColumn)
                }
            }
        }
        return GameBoard(
            board = newBoard,
            ourField = if (playerId == 0) newField else ourField,
            enemyField = if (playerId == 1) newField else enemyField
        )
    }


    private fun Point.can(direction: Direction) = when (direction) {
        UP -> canUp(this)
        DOWN -> canDown(this)
        LEFT -> canLeft(this)
        RIGHT -> canRight(this)
    }

    private fun canUp(point: Point) = canUp(point.x, point.y)
    private fun canRight(point: Point) = canRight(point.x, point.y)
    private fun canDown(point: Point) = canDown(point.x, point.y)
    private fun canLeft(point: Point) = canLeft(point.x, point.y)

    private fun canUp(x: Int, y: Int) = (y > 0) && get(y, x).connect(get(y - 1, x), UP)
    private fun canRight(x: Int, y: Int) = (x < 6) && get(y, x).connect(get(y, x + 1), RIGHT)
    private fun canDown(x: Int, y: Int) = (y < 6) && get(y, x).connect(get(y + 1, x), DOWN)
    private fun canLeft(x: Int, y: Int) = (x > 0) && get(y, x).connect(get(y, x - 1), LEFT)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GameBoard

        if (!board.contentEquals(other.board)) return false
        if (ourField != other.ourField) return false
        if (enemyField != other.enemyField) return false

        return true
    }

    override fun hashCode(): Int {
        var result = board.contentHashCode()
        result = 31 * result + ourField.hashCode()
        result = 31 * result + enemyField.hashCode()
        return result
    }
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
enum class Tile(val mask: Int, val roads: Int) {
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

    companion object {
        fun read(input: Scanner): Tile {
            val mask = Integer.parseInt(input.next(), 2)
            return values().find { it.mask == mask }!!
        }
    }

    fun connect(tile: Tile, direction: Direction): Boolean {
        return this.mask.and(direction.mask) != 0 && tile.mask.and(direction.opposite.mask) != 0
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

    fun push(direction: Direction, rowColumn: Int): Player {
        return if (direction == UP || direction == DOWN) {
            if (playerX != rowColumn) {
                this
            } else {
                val newPlayerY = (playerY + (if (direction == UP) -1 else 1) + 7) % 7
                return copy(playerY = newPlayerY)
            }
        } else {
            if (playerY != rowColumn) {
                this
            } else {
                val newPlayerX = (playerX + (if (direction == LEFT) -1 else 1) + 7) % 7
                return copy(playerX = newPlayerX)
            }
        }
    }
}

data class Item(val itemName: String, val itemPlayerId: Int) {
    companion object {
        fun Item?.questMask(playerId: Int, quests: List<String>): Int {
            if (this != null && this.itemPlayerId == playerId) {
                val indexOf = quests.indexOf(this.itemName)
                if (indexOf > -1) {
                    return (1).shl(indexOf)
                }
            }
            return 0
        }

        fun Item?.isBelongToQuest(playerId: Int, quests: List<String>): Boolean {
            return this != null && this.itemPlayerId == playerId && quests.contains(this.itemName)
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

    fun toItem() = Item(itemName, itemPlayerId)
}

class Quest(input: Scanner) {
    val questItemName: String = input.next()
    val questPlayerId = input.nextInt()
}

enum class Direction(val mask: Int, val isVertical: Boolean, val priority: Int) {
    UP(0b1000, true, priority = 0),
    RIGHT(0b0100, false, priority = 1),
    DOWN(0b0010, true, priority = 0),
    LEFT(0b0001, false, priority = 1);

    val opposite: Direction
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }

    companion object {
        val allDirections = values().toList()
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
//        var count =1
//        val oe = Array(4){IntArray(4)}
//        val fields = toPush.gameBoard.board.toMutableList()
//        fields.add(toPush.gameBoard.ourField)
//        fields.add(toPush.gameBoard.enemyField)
//        val quests =
//            fields.filter { it.item != null && it.item.itemPlayerId == 0 }.map { it.item!!.itemName }.toMutableList()
//        val domain = Domains()
//        while (true) {
//            if (count % 500000 ==0 ){
//                    for(o in (0 until 4)) {
//                        for(e in (0 until 4)){
//                            print(oe[o][e] *1.0/ count *100)
//                            print(" ")
//                        }
//                        println()
//                    }
//                println("-------------------")
//            }
//
//            fields.shuffle(rand)
//            val rboard =  GameBoard(fields.subList(0,49).toTypedArray(), fields[49], fields[50])
//            quests.shuffle(rand)
//            val ourQuest = quests.subList(0, 3).toList()
//            quests.shuffle(rand)
//            val enemyQuest = quests.subList(0, 3).toList()
//            val ourD = rboard.findDomain(
//                Point.point(rand.nextInt(7), rand.nextInt(7)),
//                ourQuest,
//                enemyQuest,
//                domain,
//                emptyList()
//            )
//            val enemyD = rboard.findDomain(
//                Point.point(rand.nextInt(7), rand.nextInt(7)),
//                ourQuest,
//                enemyQuest,
//                domain,
//                emptyList()
//            )
//            oe[ourD.getOurQuestsCount()][enemyD.getEnemyQuestsCount()]+=1
//            domain.clear()
//            count++
//        }

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
                step = -2 // small limit
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
