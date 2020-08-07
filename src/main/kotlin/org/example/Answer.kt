import Direction.*
import java.io.*
import java.util.*
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
                    0110 1010 0101 1110 1001 0111 1010
                    1001 1011 0110 1010 0101 0111 1110
                    0101 1001 0110 0111 1010 1010 1101
                    0110 0110 1010 1111 1001 0101 1001
                    0111 0101 0101 1101 1001 1001 0101
                    1011 1010 0110 1010 0101 0110 0110
                    1010 1101 1101 1011 1001 1110 1001
                    12 0 0 1010
                    12 6 6 0110
                    24
                    BOOK 5 1 0
                    POTION -2 -2 1
                    CANE 5 6 0
                    KEY 3 5 0
                    SCROLL 4 3 0
                    ARROW 4 4 1
                    DIAMOND 0 2 0
                    DIAMOND 6 4 1
                    SHIELD 3 0 0
                    CANDY 1 4 0
                    SWORD 1 2 1
                    MASK 1 0 1
                    BOOK 1 6 1
                    MASK -1 -1 0
                    POTION 4 6 0
                    FISH 1 5 0
                    FISH 5 2 1
                    CANDY 5 3 1
                    SCROLL 2 2 1
                    ARROW 2 1 0
                    CANE 1 1 1
                    SWORD 5 5 0
                    SHIELD 3 6 1
                    KEY 3 1 1
                    6
                    CANE 0
                    SCROLL 0
                    POTION 0
                    CANE 1
                    SCROLL 1
                    POTION 1
                """.trimIndent()
            )
        )

        val (turnType, gameBoard, ourQuests, enemyQuests, we, enemy) = readInput(input)
        val bestMove = findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
        println(bestMove)
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
        repeat(250) { step ->
            val (turnType, gameBoard, ourQuests, enemyQuests, we, enemy) = readInput(input)

            // Write an action using println()
            // To debug: System.err.println("Debug messages...");
            if (turnType == 0) {

//                while (true) {
                val duration = measureTimeMillis {
                    val bestMove = findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)

                    if (step == 0) {
                        findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
                        findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
                    }

                    println("PUSH ${bestMove.rowColumn} ${bestMove.direction}")
                }

                System.err.println("Duration: $duration")
//                }
            } else {
                val paths = gameBoard.findPaths(we, ourQuests)
                val bestPath = paths.maxBy { it.itemsTaken.size }
                if (bestPath != null && bestPath.itemsTaken.isNotEmpty()) {
                    val directions = mutableListOf<Direction>()
                    var pathElem: PathElem = bestPath
                    while (pathElem.prev != null) {
                        val direction = pathElem.direction!!
                        directions.add(0, direction)
                        pathElem = pathElem.prev!!
                    }
                    println("MOVE " + directions.joinToString(separator = " "))
                } else {
                    println("PASS")
                }
            }
        }
    } catch (t: Throwable) {
        t.printStackTrace()
        throw t;
    }
}

private fun readInput(input: Scanner): InputConditions {
    val turnType = input.nextInt() // 0 - push, 1 - move
    val board = Board(input)

    var we = Player(0, input)
    var enemy = Player(1, input)


    val items = (0 until input.nextInt()).map { ItemDto(input) }
    val quests = (0 until input.nextInt()).map { Quest(input) }

    we = we.copy(
        playerField = we.playerField.copy(
            we.playerField.tile,
            item = items.singleOrNull { it.isOnOurHand }?.toItem()
        )
    )
    enemy = enemy.copy(
        playerField = enemy.playerField.copy(
            enemy.playerField.tile,
            item = items.singleOrNull { it.isOnEnemyHand }?.toItem()
        )
    )

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
        })

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
)

private fun findBestPush(
    we: Player,
    enemy: Player,
    gameBoard: GameBoard,
    ourQuests: List<String>,
    enemyQuests: List<String>
): PushAction {

    val pushes = (0..6).asSequence().flatMap { rowColumn ->
        Direction.values().asSequence().flatMap { direction ->
            (0..6).asSequence().flatMap { enemyRowColumn ->
                Direction.values().asSequence().mapNotNull { enemyDirection ->
                    if (enemyRowColumn == rowColumn && (direction == enemyDirection || direction == enemyDirection.opposite)) {
                        null
                    } else {
                        data class Action(
                            val direction: Direction,
                            val rowColumn: Int,
                            val fieldOnHand: Field,
                            val isEnemy: Boolean
                        )

                        val sortedActions = listOf(
                            Action(direction, rowColumn, we.playerField, isEnemy = false),
                            Action(
                                enemyDirection,
                                enemyRowColumn,
                                enemy.playerField,
                                isEnemy = true
                            )
                        ).sortedByDescending { it.direction.priority }

                        var newBoard = gameBoard
                        var ourPlayer = we
                        var enemyPlayer = enemy
                        sortedActions.forEach { (direction, rowColumn, fieldOnHand, isEnemy) ->
                            val (board, field) = newBoard.push(fieldOnHand, direction, rowColumn)
                            newBoard = board
                            ourPlayer = ourPlayer.push(
                                direction,
                                rowColumn,
                                newField = if (isEnemy) ourPlayer.playerField else field
                            )
                            enemyPlayer = enemyPlayer.push(
                                direction,
                                rowColumn,
                                newField = if (!isEnemy) enemyPlayer.playerField else field
                            )
                        }
                        val ourPaths = newBoard.findPaths(ourPlayer, ourQuests)
                        val enemyPaths = newBoard.findPaths(enemyPlayer, enemyQuests)

                        PushAndMove(
                            ourRowColumn = rowColumn,
                            ourDirection = direction,
                            enemyRowColumn = enemyRowColumn,
                            enemyDirection = enemyDirection,
                            ourPaths = ourPaths,
                            enemyPaths = enemyPaths,
                            ourFieldOnHand = ourPlayer.playerField,
                            enemyFieldOnHand = enemyPlayer.playerField,
                            board = newBoard,
                            ourPlayer = ourPlayer,
                            enemyPlayer = enemyPlayer,
                            ourQuests = ourQuests,
                            enemyQuests = enemyQuests
                        )
                    }
                }
            }
        }
    }


    val comparator =
        compareBy<List<PushAndMove>>(PushSelectors.itemsCountDiff_50p)
            .thenComparing(PushSelectors.pushOutItemsMean)

    //todo: comparator will be invoked multiple times on same entry. Better to cache calculated values
    val (bestMove, bestScore) = pushes
        .groupBy { PushAction(it.ourDirection, it.ourRowColumn) }
        .maxWith(Comparator { left, right ->
            comparator.compare(
                left.value,
                right.value
            )
        })!!

    return bestMove
}

object PushSelectors {
    private val itemsCountLevelled = { percentLevel: Double, pushesAndMoves: List<PushAndMove> ->
        val firstThatMatch = (3 downTo -3).find { count ->
            val averageForLevel = pushesAndMoves.map {
                val enemyScore = it.enemyPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
                val ourScore = it.ourPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
                if (ourScore - enemyScore >= count) {
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
        pushesAndMoves.map {
            val enemyScore = it.enemyPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
            val ourScore = it.ourPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
            ourScore - enemyScore
        }
    }

    val itemsCountDiffMax = { pushesAndMoves: List<PushAndMove> -> itemCountDiff(pushesAndMoves).max()!! }
    val itemsCountDiffMin = { pushesAndMoves: List<PushAndMove> -> itemCountDiff(pushesAndMoves).min()!! }
    val itemsCountDiffMean = { pushesAndMoves: List<PushAndMove> -> itemCountDiff(pushesAndMoves).average() }

    private val selfItemsCount = { pushesAndMoves: List<PushAndMove> ->
        pushesAndMoves.map {
            val ourScore = it.ourPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
            ourScore
        }
    }

    val selfItemsMax = { pushesAndMoves: List<PushAndMove> -> selfItemsCount(pushesAndMoves).max()!! }
    val selfItemsMean = { pushesAndMoves: List<PushAndMove> -> selfItemsCount(pushesAndMoves).average() }

    private val pushOutItems = { pushesAndMoves: List<PushAndMove> ->

        fun Field.holdOurQuestItem(playerId: Int, quests: List<String>): Boolean {
            return this.item != null && this.item.itemPlayerId == playerId && quests.contains(this.item.itemName)
        }

        pushesAndMoves.map {
            val ourPlayerId = it.ourPlayer.playerId
            val ourQuests = it.ourQuests

            val onHandScore =
                if (it.ourFieldOnHand.holdOurQuestItem(ourPlayerId, ourQuests)) {
                    16
                } else {
                    0
                }


            val otherItemsScore = it.board.board.mapIndexedNotNull { y, row ->
                val scores = row.mapIndexedNotNull { x, field ->
                    if (field.holdOurQuestItem(ourPlayerId, ourQuests)) {
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

            onHandScore + otherItemsScore
        }
    }

    val pushOutItemsMax = { pushesAndMoves: List<PushAndMove> -> PushSelectors.pushOutItems(pushesAndMoves).max()!! }
    val pushOutItemsMin = { pushesAndMoves: List<PushAndMove> -> PushSelectors.pushOutItems(pushesAndMoves).min()!! }
    val pushOutItemsMean = { pushesAndMoves: List<PushAndMove> -> PushSelectors.pushOutItems(pushesAndMoves).average() }
}

class Board(val board: List<List<Tile>>) {

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

data class PathElem(val point: Point, val itemsTaken: Set<String>) {
    var prev: PathElem? = null
    var direction: Direction? = null
    var deep: Int = 0
}

class GameBoard(val board: List<List<Field>>) {
    companion object {
        val pooledList1 = arrayListOf<PathElem>()
        val pooledList2 = arrayListOf<PathElem>()
    }

    fun findPaths(player: Player, quests: List<String>): List<PathElem> {
        fun PathElem.move(direction: Direction): PathElem {
            val newPoint = this.point.move(direction)
            val item = board[newPoint].item
            val newItems =
                if (item != null && item.itemPlayerId == player.playerId && quests.contains(item.itemName)) {
                    this.itemsTaken + item.itemName
                } else {
                    this.itemsTaken
                }
            return copy(point = newPoint, itemsTaken = newItems).apply {
                this.prev = this@move
                this.direction = direction
                this.deep = this@move.deep + 1
            }
        }

        val initialItem = board[player.point].item
        val initial =
            if (initialItem != null && initialItem.itemPlayerId == player.playerId && quests.contains(
                    initialItem.itemName
                )
            ) {
                PathElem(player.point, setOf(initialItem.itemName))
            } else {
                PathElem(player.point, emptySet())
            }

        val visited = mutableMapOf(initial to DOWN)

        pooledList1.clear()
        pooledList2.clear()
        var front = pooledList1
        front.add(initial)


        repeat(20) {
            if (front.isEmpty()) {
                return@repeat
            }

            val newFront = if (front == pooledList1) pooledList2 else pooledList1
            newFront.clear()

            for (pathElem in front) {
                for (direction in Direction.values()) {
                    if (!pathElem.point.can(direction)) {
                        continue
                    }
                    val newPathElem = pathElem.move(direction)
                    if (visited.containsKey(newPathElem)) {
                        continue
                    }
                    visited[newPathElem] = direction
                    newFront.add(newPathElem)
                }
            }
            front = newFront
        }

        return visited.keys.toList()
    }

    fun push(field: Field, direction: Direction, rowColumn: Int): Pair<GameBoard, Field> {
        if (direction == LEFT || direction == RIGHT) {
            val mutableBoard = board.toMutableList()
            val newField: Field
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
            return GameBoard(mutableBoard) to newField
        } else {
            val shift: Int
            val newField: Field
            if (direction == UP) {
                shift = 1
                newField = board[0][rowColumn]
            } else {
                shift = -1
                newField = board[6][rowColumn]
            }
            val newBoard = board.mapIndexed { y, row ->
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
            return GameBoard(newBoard) to newField
        }
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


data class Point(val x: Int, val y: Int) {
    fun moveUp() = copy(y = y - 1)
    fun moveDown() = copy(y = y + 1)
    fun moveLeft() = copy(x = x - 1)
    fun moveRight() = copy(x = x + 1)

    fun move(direction: Direction) = when (direction) {
        UP -> moveUp()
        DOWN -> moveDown()
        LEFT -> moveLeft()
        RIGHT -> moveRight()
    }
}

enum class Tile(val mask: Int) {
    T0011(0b0011),
    T0101(0b0101),
    T0110(0b0110),
    T1001(0b1001),
    T1010(0b1010),
    T1100(0b1100),
    T0111(0b0111),
    T1011(0b1011),
    T1101(0b1101),
    T1110(0b1110),
    T1111(0b1111);

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
    val playerY: Int,
    val playerField: Field
) {

    constructor(playerId: Int, input: Scanner) : this(
        playerId = playerId,
        numPlayerCards = input.nextInt(), // the total number of quests for a player (hidden and revealed)
        playerX = input.nextInt(),
        playerY = input.nextInt(),
        playerField = Field(Tile.read(input), item = null) // to be updated after items will be read
    )

    val point: Point = Point(playerX, playerY)

    fun push(direction: Direction, rowColumn: Int, newField: Field): Player {
        return if (direction == UP || direction == DOWN) {
            if (playerX != rowColumn && newField == playerField) {
                this
            } else if (playerX != rowColumn) {
                copy(playerField = newField)
            } else {
                val newPlayerY = (playerY + (if (direction == UP) -1 else 1) + 7) % 7
                return copy(playerY = newPlayerY, playerField = newField)
            }
        } else {
            if (playerY != rowColumn && newField == playerField) {
                this
            } else if (playerY != rowColumn) {
                copy(playerField = newField)
            } else {
                val newPlayerX = (playerX + (if (direction == LEFT) -1 else 1) + 7) % 7
                return copy(playerX = newPlayerX, playerField = newField)
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

    val point: Point = Point(itemX, itemY)
    val isOnHand: Boolean = itemX < 0
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
