import Direction.*
import Item.Companion.isBelongToQuest
import PushSelectors.itemsCountDiff
import PushSelectors.pushOutItems
import PushSelectors.space
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.util.*
import java.util.concurrent.TimeUnit
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import kotlin.collections.ArrayList
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.measureTimeMillis


object Test {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = Scanner(
            StringReader(
                """""".trimIndent()
            )
        )

        val (turnType, gameBoard, ourQuests, enemyQuests, we, enemy) = readInput(input, 0)
        while (true) {
            val duration = measureTimeMillis {
                val bestMove = findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests, null, null, 0)
                println(bestMove)
            }
            println(duration)
        }
    }
}

/**
 * Help the Christmas elves fetch presents in a magical labyrinth!
 **/
fun main(args: Array<String>) {
    performGame()
}

val startTime = System.currentTimeMillis()
fun log(s: Any?) {
    System.err.println("\n#[${System.currentTimeMillis() - startTime}] $s\n")
}

fun setupMonitoring() {
    log("java started as: ${ManagementFactory.getRuntimeMXBean().inputArguments}")

    val garbageCollectorMXBeans: Collection<GarbageCollectorMXBean> =
        ManagementFactory.getGarbageCollectorMXBeans()


    garbageCollectorMXBeans.forEach { bean ->

        var lastCount = bean.collectionCount
        var lastTime = bean.collectionTime
        val listener = NotificationListener { _, _ ->
            val newCount = bean.collectionCount
            val newTime = bean.collectionTime
            log("GC: ${bean.name} collectionsCount=${newCount - lastCount} collectionsTime=${newTime - lastTime}ms")
            lastCount = newCount
            lastTime = newTime
        }
        (bean as NotificationEmitter).addNotificationListener(
            listener,
            null,
            "haha"
        )
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

fun performGame() {
    setupMonitoring()

    try {
//        val input = Scanner(System.`in`)
        val input = Scanner(TeeInputStream(System.`in`, System.err))

//        val input = Scanner(
//            StringReader(
//                """""".trimIndent()
//            )
//        )

        // game loop
        var lastBoard: GameBoard? = null
        var lastPush: OnePush? = null
        var wasDrawAtPrevMove = false

        data class Actions(val ourAction: OnePush, val enemyAction: OnePush)

        val allBoards = mutableMapOf<GameBoard, Actions>()
        val moveScores = Point.points.flatten()
            .map { it to 0 }
            .toMap().toMutableMap()

        repeat(150) { step ->
            BoardCache.reset()

            log("step $step")
            val start = System.currentTimeMillis()
            val (turnType, gameBoard, ourQuests, enemyQuests, we, enemy) = readInput(input, step)

            // Write an action using println()
            // To debug: System.err.println("Debug messages...");
            if (turnType == 0) {
                val duration = measureTimeMillis {
                    val prevMovesAtThisPosition = allBoards[gameBoard]

                    val expectedEnemyMoves = if (prevMovesAtThisPosition != null) { // we have seen it
                        listOf(prevMovesAtThisPosition.enemyAction)
                    } else {
                        if (wasDrawAtPrevMove) {
                            listOf(lastPush!!, lastPush!!.copy(direction = lastPush!!.direction.opposite))
                        } else {
                            null
                        }
                    }

                    log("prevDraw=$wasDrawAtPrevMove duplicate=${prevMovesAtThisPosition != null}")

                    val bestMove = findBestPush(
                        we,
                        enemy,
                        gameBoard,
                        ourQuests,
                        enemyQuests,
                        if (wasDrawAtPrevMove) lastPush else prevMovesAtThisPosition?.ourAction,
                        expectedEnemyMoves,
                        step
                    )

                    if (step == 0) {
                        val warmUp = (0..6).map {
                            findBestPush(
                                we,
                                enemy,
                                gameBoard,
                                ourQuests,
                                enemyQuests,
                                lastPush,
                                expectedEnemyMoves,
                                step = -2 // small limit
                            )
                        }
                        log(warmUp)
                    }

                    println("PUSH ${bestMove.rowColumn} ${bestMove.direction}")

                    lastBoard = gameBoard
                    lastPush = bestMove
                }

                log("Duration: $duration")
//                }
            } else {
                val duration = measureTimeMillis {
                    val lastPush = lastPush!!
                    val lastBoard = lastBoard!!
                    wasDrawAtPrevMove = lastBoard == gameBoard
                    if (!wasDrawAtPrevMove) {
                        val enemyLastPush = tryFindEnemyPush(lastBoard, gameBoard, lastPush)
                        if (enemyLastPush != null) {
                            allBoards.put(lastBoard, Actions(lastPush, enemyLastPush))
                        }
                        null // we do not know enemy move
                    }

                    val paths = gameBoard.findPaths(we, ourQuests)

                    val itemsTaken = paths.maxWith(compareBy { it.itemsTaken.size })!!.itemsTaken.size
                    val ends = paths.filter { it.itemsTaken.size == itemsTaken }
                        .map { it.point }.toHashSet()
                    ends.forEach { moveScores[it] = 0 }
                    if (true) {
                        val pushes = computePushes(
                            gameBoard,
                            we,
                            ourQuests,
                            enemy = enemy,
                            enemyQuests = enemyQuests,
                            timeLimitNanos = TimeUnit.MILLISECONDS.toNanos(if (step == 0) 500 else 40)
                        )
                        val domains = Domains()
                        pushes.filter { it.board !== gameBoard }
                            .forEach { push ->
                                domains.clear()
                                ends.forEach { point ->
                                    var fake = Player(-1, -1, point.x, point.y)
                                    val pushP = if (push.pushes.ourPush.direction.isVertical) {
                                        fake =
                                            fake.push(push.pushes.enemyPush.direction, push.pushes.enemyPush.rowColumn)
                                        fake = fake.push(push.pushes.ourPush.direction, push.pushes.ourPush.rowColumn)
                                        fake.point
                                    } else {
                                        fake = fake.push(push.pushes.ourPush.direction, push.pushes.ourPush.rowColumn)
                                        fake =
                                            fake.push(push.pushes.enemyPush.direction, push.pushes.enemyPush.rowColumn)
                                        fake.point
                                    }
                                    val domain = push.board.findDomain(pushP, ourQuests, enemyQuests, domains)
                                    val score = domain.ourQuests * 12 + domain.size + domain.ourItems
                                    moveScores[point] = moveScores[point]!! + score
                                }
                            }
                    }
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
                log("Duration: $duration")
            }
        }
    } catch (t: Throwable) {
        t.printStackTrace()
        throw t
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

private fun readInput(input: Scanner, step: Int): InputConditions {
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

    val boardArray = Array<Field>(7 * 7) { idx ->
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
    ).apply {
        this.step = step
    }

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

data class OnePush(val direction: Direction, val rowColumn: Int)

data class Pushes(val ourPush: OnePush, val enemyPush: OnePush) {
    companion object {
        val allPushes: List<Pushes> =
            (0..6).flatMap { ourRowColumn ->
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

    val ourDomain = board.findDomain(ourPlayer.point, ourQuests, enemyQuests, pooledDomains)
    val enemyDomain = board.findDomain(enemyPlayer.point, ourQuests, enemyQuests, pooledDomains)
    val ourFieldOnHand = board.ourField
    val enemyFieldOnHand = board.enemyField

//    val ourPaths: List<PathElem> = board.findPaths(ourPlayer, ourQuests)
//    val enemyPaths: List<PathElem> = board.findPaths(enemyPlayer, enemyQuests)
//    val enemySpace = enemyPaths.groupBy { it.point }.size
//    val ourSpace = ourPaths.groupBy { it.point }.size
//    val enemyQuestCompleted = enemyPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
//    val ourQuestCompleted = ourPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size

    val enemySpace = enemyDomain.size
    val ourSpace = ourDomain.size
    val enemyQuestCompleted = enemyDomain.enemyQuests
    val ourQuestCompleted = ourDomain.ourQuests

//    val estimate: Int = (ourQuestCompleted - enemyQuestCompleted + 3).shl(1)
//        .or(if (ourFieldOnHand.item?.itemPlayerId ?: -1 == 0) 1 else 0).shl(7)
//        .or(ourSpace - enemySpace + 47)
}

data class Action(val push: OnePush, val isEnemy: Boolean)

private fun findBestPush(
    we: Player,
    enemy: Player,
    gameBoard: GameBoard,
    ourQuests: List<String>,
    enemyQuests: List<String>,
    ourPushInSamePosition: OnePush? = null,
    expectedEnemyMoves: List<OnePush>?,
    step: Int
): OnePush {
    val weLoseOrDrawAtEarlyGame = (we.numPlayerCards > enemy.numPlayerCards
            || (we.numPlayerCards == enemy.numPlayerCards && gameBoard.step < 50))

    val weHaveSeenThisPositionBefore = ourPushInSamePosition != null
    val forbiddenPushMoves = if (weHaveSeenThisPositionBefore && weLoseOrDrawAtEarlyGame) {
        listOf(
            ourPushInSamePosition!!,
            ourPushInSamePosition.copy(direction = ourPushInSamePosition.direction.opposite)
        )
    } else {
        emptyList()
    }

    val pushes = computePushes(
        gameBoard,
        we,
        ourQuests,
        forbiddenPushMoves,
        enemy,
        enemyQuests,
        expectedEnemyMoves,
        timeLimitNanos = TimeUnit.MILLISECONDS.toNanos(if (step == 0) 500 else 40)
    )

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
        .groupBy { it.pushes.enemyPush }
        .toList()
        .sortedWith(Comparator { left, right ->
            enemyComparator.compare(
                left.second,
                right.second
            )
        })
        .take(7)
        .map { it.first }

    val (bestMove, bestScore) = pushes
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
    expectedEnemyMoves: List<OnePush>? = null,
    timeLimitNanos: Long
): MutableList<PushAndMove> {
    val startTime = System.nanoTime()

    val enemyDirections = expectedEnemyMoves?.map { it.direction } ?: Direction.allDirections
    val enemyRowColumns = expectedEnemyMoves?.map { it.rowColumn } ?: 0..6
    val result = mutableListOf<PushAndMove>()
    for (pushes in Pushes.allPushes) {
        if (System.nanoTime() - startTime > timeLimitNanos) {
            log("stop computePushes, computed ${result.size} pushes")
            return result
        }
        if (!enemyDirections.contains(pushes.enemyPush.direction)) {
            continue
        }
        if (!enemyRowColumns.contains(pushes.enemyPush.rowColumn)) {
            continue
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
        emptyList<Action>()
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

class PushResultTable(pushes: List<PushAndMove>) {
    data class PushResult(val itemsCountDiff: Int, val pushOutItems: Int, val space: Int)

    fun calcPushResult(push: PushAndMove): PushResult {
        return PushResult(
            itemsCountDiff(push),
            pushOutItems(push),
            space(push)
        )
    }

    val table = pushes
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
        return push.ourQuestCompleted - push.enemyQuestCompleted
    }

    private val itemsCountLevelled = { percentLevel: Double, pushesAndMoves: List<PushAndMove> ->
        val firstThatMatch = (3 downTo -3).find { count ->
            val averageForLevel = pushesAndMoves.map {
                if (itemsCountDiff(it) >= count) {
                    1
                } else {
                    0
                }
            }.average()

            averageForLevel >= percentLevel
        }
        firstThatMatch
    }

    val itemsCountDiff_50p = { pushesAndMoves: List<PushAndMove> -> itemsCountLevelled(0.50, pushesAndMoves) }
    val itemsCountDiff_75p = { pushesAndMoves: List<PushAndMove> -> itemsCountLevelled(0.75, pushesAndMoves) }

    private val itemCountDiff = { pushesAndMoves: List<PushAndMove> ->
        pushesAndMoves.map(this::itemsCountDiff)
    }

    val itemsCountDiffMax = { pushesAndMoves: List<PushAndMove> -> itemCountDiff(pushesAndMoves).max()!! }
    val itemsCountDiffMin = { pushesAndMoves: List<PushAndMove> -> itemCountDiff(pushesAndMoves).min()!! }
    val itemsCountDiffAvg = { pushesAndMoves: List<PushAndMove> -> itemCountDiff(pushesAndMoves).average() }

    private val selfItemsCount = { pushesAndMoves: List<PushAndMove> ->
        pushesAndMoves.map {
            val ourScore = it.ourQuestCompleted
            ourScore
        }
    }

    val selfItemsMax = { pushesAndMoves: List<PushAndMove> -> selfItemsCount(pushesAndMoves).max()!! }
    val selfItemsAvg = { pushesAndMoves: List<PushAndMove> -> selfItemsCount(pushesAndMoves).average() }

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

    val pushOutItemsMax = { pushesAndMoves: List<PushAndMove> -> pushOutItems(pushesAndMoves).max()!! }
    val pushOutItemsMin = { pushesAndMoves: List<PushAndMove> -> pushOutItems(pushesAndMoves).min()!! }
    val pushOutItemsAvg = { pushesAndMoves: List<PushAndMove> -> pushOutItems(pushesAndMoves).average() }

    fun space(push: PushAndMove): Int {
        return push.ourSpace - push.enemySpace
    }

    val space = { pushesAndMoves: List<PushAndMove> ->
        pushesAndMoves.map { push ->
            push.ourSpace - push.enemySpace
        }
    }

    val spaceMax = { pushesAndMoves: List<PushAndMove> -> space(pushesAndMoves).max()!! }
    val spaceMin = { pushesAndMoves: List<PushAndMove> -> space(pushesAndMoves).min()!! }
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
    val ourQuests: Int,
    val enemyQuests: Int,
    val ourItems: Int,
    val enemyItems: Int
) {
    companion object {
        val empty = DomainInfo(0, 0, 0, 0, 0)
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
    var step: Int = 0
    val cachedPaths = arrayOfNulls<MutableList<PathElem>>(2)

    companion object {
        val pooledList1 = arrayListOf<PathElem>()
        val pooledList2 = arrayListOf<PathElem>()
        val pooledDomains: Array<IntArray> = Array(7) { IntArray(7) }
        val visitedPoints = BitSet(50)
        val pooledFront:MutableList<Point> = ArrayList(50)
    }

    fun get(y: Int, x: Int) = board[y * 7 + x]
    operator fun get(point: Point) = get(point.y, point.x)

    fun findDomain(
        point: Point,
        ourQuests: List<String>,
        enemyQuests: List<String>,
        domains: Domains
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
        var ourQuestsCount = 0
        var enemyQuestsCount = 0
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
            ourQuestsCount += if (item.isBelongToQuest(0, ourQuests)) 1 else 0
            enemyQuestsCount += if (item.isBelongToQuest(1, enemyQuests)) 1 else 0
            ourItem += if (item != null && item.itemPlayerId == 0) 1 else 0
            enemyItem += if (item != null && item.itemPlayerId == 1) 1 else 0
            for (direction in Direction.allDirections) {
                if (nextPoint.can(direction)) {
                    val newPoint = nextPoint.move(direction)
                    if (!visitedPoints.get(newPoint.idx)) {
                        visitedPoints.set(newPoint.idx)
                        pooledFront.add(newPoint)
                    }
                }
            }
        }
        val domain = DomainInfo(size, ourQuestsCount, enemyQuestsCount, ourItem, enemyItem)


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
                val firstItem = if (quests.size > 0 && newItems.contains(quests[0])) 1 else 0
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
        ).apply { this.step = this@GameBoard.step + 1 }
    }


    fun Point.can(direction: Direction) = when (direction) {
        UP -> canUp(this)
        DOWN -> canDown(this)
        LEFT -> canLeft(this)
        RIGHT -> canRight(this)
    }

    fun canUp(point: Point) = canUp(point.x, point.y)
    fun canRight(point: Point) = canRight(point.x, point.y)
    fun canDown(point: Point) = canDown(point.x, point.y)
    fun canLeft(point: Point) = canLeft(point.x, point.y)

    fun canUp(x: Int, y: Int) = (y > 0) && get(y, x).connect(get(y - 1, x), UP)
    fun canRight(x: Int, y: Int) = (x < 6) && get(y, x).connect(get(y, x + 1), RIGHT)
    fun canDown(x: Int, y: Int) = (y < 6) && get(y, x).connect(get(y + 1, x), DOWN)
    fun canLeft(x: Int, y: Int) = (x > 0) && get(y, x).connect(get(y, x - 1), LEFT)

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

        val point_minus2 = Point(-2, -2)
        val point_minus1 = Point(-1, -1)

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
    val questItemName = input.next()
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
        val allDirections = Direction.values().toList()
    }
}


fun <T> List<List<T>>.transpose(): List<List<T>> {
    return (this[0].indices).map { x ->
        (this.indices).map { y ->
            this[y][x]
        }
    }
}

class TeeInputStream(protected var source: InputStream, protected var copySink: OutputStream) : InputStream() {
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
