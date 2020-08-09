import Direction.*
import PushSelectors.itemsCountDiff
import PushSelectors.pushOutItems
import PushSelectors.space
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.util.*
import kotlin.collections.average
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.max
import kotlin.collections.min
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.measureTimeMillis


object Test {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = Scanner(
            StringReader(
                """
                    0
                    0110 1001 1010 0101 0110 1001 1010
                    1101 1010 1101 1001 0111 1011 0110
                    0101 1010 0101 1001 0011 1010 1001
                    0101 0111 1110 1010 1011 1101 0101
                    0110 1010 1100 0110 0101 1010 0101
                    1001 1110 1101 0110 0111 1010 0111
                    1010 0110 1001 0101 1010 0110 1001
                    12 0 0 0111
                    12 6 6 1101
                    24
                    CANE 4 4 0
                    KEY 1 6 0
                    SCROLL 2 3 1
                    MASK 6 4 0
                    SHIELD 6 0 1
                    SHIELD 0 6 0
                    POTION 3 2 0
                    CANE 2 2 1
                    POTION 3 4 1
                    SWORD 1 0 0
                    BOOK 5 5 0
                    ARROW 0 1 1
                    DIAMOND 5 4 0
                    FISH 4 0 0
                    CANDY 3 6 0
                    KEY 5 0 1
                    BOOK 1 1 1
                    ARROW 6 5 0
                    MASK 0 2 1
                    FISH 2 6 1
                    CANDY 3 0 1
                    SCROLL 4 3 0
                    SWORD 5 6 1
                    DIAMOND 1 2 1
                    6
                    CANE 0
                    ARROW 0
                    CANDY 0
                    CANE 1
                    ARROW 1
                    CANDY 1
                """.trimIndent()
            )
        )

        val (turnType, gameBoard, ourQuests, enemyQuests, we, enemy) = readInput(input, 0)
        while (true) {
            val duration = measureTimeMillis {
                val bestMove = findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
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
        var lastPush: PushAction? = null
        var turn = 0



        repeat(150) { step ->
            val (turnType, gameBoard, ourQuests, enemyQuests, we, enemy) = readInput(input, turn)
            turn++

            // Write an action using println()
            // To debug: System.err.println("Debug messages...");
            if (turnType == 0) {

//                while (true) {
                val duration = measureTimeMillis {
                    val bestMove = if (lastBoard != null && lastBoard!!.board == gameBoard.board) {
                        findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests, lastPush)
                    } else {
                        findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
                    }

                    if (step == 0) {
                        findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
                        findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
                        findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
                        findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
                        findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
                        findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
                    }

                    println("PUSH ${bestMove.rowColumn} ${bestMove.direction}")

                    lastBoard = gameBoard
                    lastPush = bestMove
                }

                System.err.println("Duration: $duration")
//                }
            } else {
                val paths = gameBoard.findPaths(we, ourQuests)

                val pathsComparator = compareBy<PathElem> { pathElem ->
                    pathElem.itemsTaken.size
                }.thenComparing { pathElem ->
                    max(abs(pathElem.point.x - 3), abs(pathElem.point.y - 3))
                }.thenComparing { pathElem ->
                    gameBoard.board[pathElem.point].tile.roads
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
        }
    } catch (t: Throwable) {
        t.printStackTrace()
        throw t
    }
}

private fun readInput(input: Scanner, turn: Int): InputConditions {
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

    val gameBoard = GameBoard(
        board.board.mapIndexed { y, row ->
            row.mapIndexed { x, cell ->
                Field(
                    cell,
                    items.singleOrNull { it.itemX == x && it.itemY == y }?.toItem()
                )
            }
        }, ourField, enemyField, turn
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

data class PushAction(val direction: Direction, val rowColumn: Int)


data class PushAndMove(
    val ourRowColumn: Int,
    val ourDirection: Direction,
    val enemyRowColumn: Int,
    val enemyDirection: Direction,
    val ourPaths: List<PathElem>,
    val enemyPaths: List<PathElem>,
    val ourFieldOnHand: Field,
    val enemyFieldOnHand: Field,
    val board: GameBoard,
    val ourPlayer: Player,
    val enemyPlayer: Player,
    val ourQuests: List<String>,
    val enemyQuests: List<String>
){
    val enemyAction = PushAction(enemyDirection, enemyRowColumn)
    val ourAction = PushAction(ourDirection, ourRowColumn)
}

private fun findBestPush(
    we: Player,
    enemy: Player,
    gameBoard: GameBoard,
    ourQuests: List<String>,
    enemyQuests: List<String>,
    lastPushOnDraw: PushAction? = null
): PushAction {

    val pushes = mutableListOf<PushAndMove>()


    val enemyDirections = if (lastPushOnDraw != null) {
        listOf(lastPushOnDraw.direction, lastPushOnDraw.direction.opposite)
    } else {
        Direction.allDirections
    }
    val enemyRowColumns = if (lastPushOnDraw != null) {
        (lastPushOnDraw.rowColumn..lastPushOnDraw.rowColumn)
    } else {
        (0..6)
    }

    val weLoseOrDrawAtEarlyGame = (we.numPlayerCards > enemy.numPlayerCards
            || (we.numPlayerCards == enemy.numPlayerCards && gameBoard.turn < 50))
    val forbiddenPushMoves = if (lastPushOnDraw != null &&
        weLoseOrDrawAtEarlyGame
    ) {
        listOf(lastPushOnDraw, lastPushOnDraw.copy(direction = lastPushOnDraw.direction.opposite))
    } else {
        emptyList()
    }

    for (rowColumn in (0..6)) {
        for (direction in Direction.allDirections) {
            if (forbiddenPushMoves.contains(PushAction(direction, rowColumn))) {
                continue
            }
            for (enemyRowColumn in enemyRowColumns) {
                for (enemyDirection in enemyDirections) {
                    val draw =
                        enemyRowColumn == rowColumn && (direction == enemyDirection || direction == enemyDirection.opposite)

                    data class Action(
                        val direction: Direction,
                        val rowColumn: Int,
                        val isEnemy: Boolean
                    )

                    val sortedActions = if (draw) {
                        emptyList<Action>()
                    } else {
                        listOf(
                            Action(direction, rowColumn, isEnemy = false),
                            Action(enemyDirection, enemyRowColumn, isEnemy = true)
                        ).sortedByDescending { it.direction.priority }
                    }

                    var newBoard = gameBoard
                    var ourPlayer = we
                    var enemyPlayer = enemy
                    sortedActions.forEach { (direction, rowColumn, isEnemy) ->
                        val pushPlayer = if (isEnemy) enemyPlayer else ourPlayer
                        val board = newBoard.push(pushPlayer, direction, rowColumn)
                        newBoard = board
                        ourPlayer = ourPlayer.push(
                            direction,
                            rowColumn
                        )
                        enemyPlayer = enemyPlayer.push(
                            direction,
                            rowColumn
                        )
                    }
                    val ourPaths = newBoard.findPaths(ourPlayer, ourQuests)
                    val enemyPaths = newBoard.findPaths(enemyPlayer, enemyQuests)

                    pushes.add(
                        PushAndMove(
                            ourRowColumn = rowColumn,
                            ourDirection = direction,
                            enemyRowColumn = enemyRowColumn,
                            enemyDirection = enemyDirection,
                            ourPaths = ourPaths,
                            enemyPaths = enemyPaths,
                            ourFieldOnHand = newBoard.ourField,
                            enemyFieldOnHand = newBoard.enemyField,
                            board = newBoard,
                            ourPlayer = ourPlayer,
                            enemyPlayer = enemyPlayer,
                            ourQuests = ourQuests,
                            enemyQuests = enemyQuests
                        )
                    )

                }
            }
        }
    }

    val comparator =
        caching(PushSelectors.itemsCountDiffMin)
            .thenComparing(caching(PushSelectors.itemsCountDiffAvg))
            .thenComparing(caching(PushSelectors.pushOutItemsAvg))
            .thenComparing(caching(PushSelectors.spaceAvg))

    val enemyBestMoves = pushes
        .groupBy { it.enemyAction }
        .toList()
        .sortedWith(Comparator { left, right ->
            comparator.compare(
                left.second,
                right.second
            )
        })
        .take(5)
        .map { it.first }

    val (bestMove, bestScore) = pushes
        .filter { enemyBestMoves.contains(it.enemyAction) }
        .groupBy { it.ourAction }
        .maxWith(Comparator { left, right ->
            comparator.compare(
                left.value,
                right.value
            )
        })!!

//    println(PushResultTable(pushes))

    return bestMove
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
        .groupBy { PushAction(it.ourDirection, it.ourRowColumn) }
        .mapValues { it.value.map { PushAction(it.enemyDirection, it.enemyRowColumn) to calcPushResult(it) }.toMap() }

    override fun toString(): String {
        val header =
            "our\\enemy |  " + Direction.allDirections.flatMap { dir -> (0..6).map { rc -> "${dir.name.padStart(5)}$rc" } }
                .joinToString(separator = "    | ")
        val rows = Direction.allDirections.flatMap { dir ->
            (0..6).map { rc ->
                val header = "  ${dir.name.padStart(5)}$rc  | "
                val columns = Direction.allDirections.flatMap { enemyDir ->
                    (0..6).map { enemyRc ->
                        val pushResult = table[PushAction(dir, rc)]!![PushAction(enemyDir, enemyRc)]!!
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
        val enemyScore = push.enemyPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
        val ourScore = push.ourPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
        return ourScore - enemyScore
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
            val ourScore = it.ourPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
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
            if (push.ourFieldOnHand.holdOurQuestItem(playerId, quests)) {
                16
            } else {
                0
            }

        val otherItemsScore = push.board.board.mapIndexedNotNull { y, row ->
            val scores = row.mapIndexedNotNull { x, field ->
                if (field.holdOurQuestItem(playerId, quests)) {
                    max(abs(3 - x), abs(3 - y)) * max(abs(3 - x), abs(3 - y))
                } else {
                    null
                }
            }

            if (scores.isEmpty()) {
                null
            } else {
                scores.sum()
            }
        }.sum()

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
        val enemySpace = push.enemyPaths.groupBy { it.point }.size
        val ourSpace = push.ourPaths.groupBy { it.point }.size
        return ourSpace - enemySpace
    }

    val space = { pushesAndMoves: List<PushAndMove> ->
        pushesAndMoves.map { push ->
            val enemySpace = push.enemyPaths.groupBy { it.point }.size
            val ourSpace = push.ourPaths.groupBy { it.point }.size
            ourSpace - enemySpace
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

}

data class PathElem(val point: Point, val itemsTaken: Set<String>, var prev: PathElem?, var direction: Direction?)

class GameBoard(val board: List<List<Field>>, val ourField: Field, val enemyField: Field, val turn: Int) {
    val fields = arrayOf(ourField, enemyField)

    companion object {
        val pooledList1 = arrayListOf<PathElem>()
        val pooledList2 = arrayListOf<PathElem>()
    }

    fun findPaths(player: Player, quests: List<String>): List<PathElem> {
        fun coordInVisited(newPoint: Point, newItems: Collection<String>): Int {
            val x = newPoint.x
            val y = newPoint.y
            val firstItem = if (quests.size > 0 && newItems.contains(quests[0])) 1 else 0
            val secondItem = if (quests.size > 1 && newItems.contains(quests[1])) 1 else 0
            val thirdItem = if (quests.size > 2 && newItems.contains(quests[2])) 1 else 0

            return (((x * 7 + y) * 2 + firstItem) * 2 + secondItem) * 2 + thirdItem
        }

        val initialItem = board[player.point].item
        val initial =
            if (initialItem != null && initialItem.itemPlayerId == player.playerId && quests.contains(
                    initialItem.itemName
                )
            ) {
                PathElem(player.point, setOf(initialItem.itemName), null, null)
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
                    val item = board[newPoint].item
                    val newItems =
                        if (item != null && item.itemPlayerId == player.playerId && quests.contains(item.itemName)) {
                            pathElem.itemsTaken + item.itemName
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

        return result
    }

    fun push(player: Player, direction: Direction, rowColumn: Int): GameBoard {
        val field = fields[player.playerId]
        val newBoard: List<List<Field>>
        val newField: Field
        if (direction == LEFT || direction == RIGHT) {
            val mutableBoard = board.toMutableList()
            if (direction == LEFT) {
                newField = board[rowColumn][0]
                mutableBoard[rowColumn] = board[rowColumn].toMutableList().apply {
                    this.removeAt(0)
                    this.add(6, field)
                }
            } else {
                newField = board[rowColumn].last()
                mutableBoard[rowColumn] = board[rowColumn].toMutableList().apply {
                    this.removeAt(6)
                    this.add(0, field)
                }
            }
            newBoard = mutableBoard
        } else {
            val shift: Int
            if (direction == UP) {
                shift = 1
                newField = board[0][rowColumn]
            } else {
                shift = -1
                newField = board[6][rowColumn]
            }
            newBoard = board.mapIndexed { y, row ->
                row.toMutableList().apply {
                    this[rowColumn] = if (y == 0 && direction == DOWN) {
                        field
                    } else if (y == 6 && direction == UP) {
                        field
                    } else {
                        board[y + shift][rowColumn]
                    }
                }
            }
        }
        val newFields = fields.copyOf()
        newFields[player.playerId] = newField
        return GameBoard(newBoard, newFields[0], newFields[1], turn + 1)
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

    fun canUp(x: Int, y: Int) = (y > 0) && board[y][x].connect(board[y - 1][x], UP)
    fun canRight(x: Int, y: Int) = (x < 6) && board[y][x].connect(board[y][x + 1], RIGHT)
    fun canDown(x: Int, y: Int) = (y < 6) && board[y][x].connect(board[y + 1][x], DOWN)
    fun canLeft(x: Int, y: Int) = (x > 0) && board[y][x].connect(board[y][x - 1], LEFT)
}


private operator fun List<List<Field>>.get(point: Point): Field {
    return this[point.y][point.x]
}


class Point private constructor(val x: Int, val y: Int) {
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

data class Item(val itemName: String, val itemPlayerId: Int)

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
        copySink.write(result)
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
        copySink.write(b, off, result)
        return result
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray): Int {
        val result = source.read(b)
        copySink.write(b, 0, result)
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
