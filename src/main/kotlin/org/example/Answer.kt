import Direction.*
import java.io.*
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.measureTimeMillis

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
        while (true) {
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

            // Write an action using println()
            // To debug: System.err.println("Debug messages...");
            if (turnType == 0) {

//                while (true) {
                val duration = measureTimeMillis {
                    val bestMove = findBestPush(we, enemy, gameBoard, ourQuests, enemyQuests)
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

data class PushAction(val direction: Direction, val rowColumn: Int)

private fun findBestPush(
    we: Player,
    enemy: Player,
    gameBoard: GameBoard,
    ourQuests: List<String>,
    enemyQuests: List<String>
): PushAction {
    data class PushAndMove(
        val ourRowColumn: Int,
        val ourDirection: Direction,
        val enemyRowColumn: Int,
        val enemyDirection: Direction,
        val ourPaths: List<PathElem>,
        val enemyPaths: List<PathElem>,
        val ourFieldOnHand: Field,
        val enemyFieldOnHand: Field,
        val board: GameBoard
    )

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
                            board = newBoard
                        )
                    }
                }
            }
        }
    }
    val comparator =
        compareBy<Map.Entry<PushAction, List<PushAndMove>>> { (_, pushesAndMoves) ->
            // item score
            pushesAndMoves.map {
                val enemyScore = it.enemyPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
                val ourScore = it.ourPaths.maxBy { it.itemsTaken.size }!!.itemsTaken.size
                ourScore - enemyScore
            }.min()!!

        }.thenComparingDouble { (_, pushesAndMoves) ->
            // pushOutScore
            pushesAndMoves.map {
                val onHandScore = if (it.ourFieldOnHand.item == null) {
                    0
                } else {
                    4
                }

                val otherItemsScore = it.board.board.mapIndexedNotNull { y, row ->
                    val scores = row.mapIndexedNotNull { x, field ->
                        if (field.item == null || field.item.itemPlayerId != we.playerId || !ourQuests.contains(field.item.itemName)) {
                            null
                        } else {
                            max(abs(3 - x), abs(3 - y))
                        }
                    }

                    if (scores.isEmpty()) {
                        null
                    } else {
                        scores.sum()
                    }
                }.sum()

                onHandScore + otherItemsScore

            }.average()
        }

    val (bestMove, bestScore) = pushes
        .groupBy { PushAction(it.ourDirection, it.ourRowColumn) }
        .maxWith(comparator)!!

    return bestMove
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
            val newItems = if (item != null && item.itemPlayerId == player.playerId && quests.contains(item.itemName)) {
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
            if (initialItem != null && initialItem.itemPlayerId == player.playerId && quests.contains(initialItem.itemName)) {
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
            front.asSequence().flatMap { pathElem ->
                Direction.values()
                    .asSequence()
                    .filter { pathElem.point.can(it) }
                    .map { pathElem.move(it) to it }
                    .filterNot { (pathElem, _) -> visited.containsKey(pathElem) }
                    .map { (pathElem, direction) ->
                        visited[pathElem] = direction
                        pathElem
                    }
            }.toCollection(if (front == pooledList1) pooledList2 else pooledList1)
            front.clear()
            front = if (front == pooledList1) pooledList2 else pooledList1
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
                        row[y + shift]
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
