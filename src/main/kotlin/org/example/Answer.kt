import Direction.*
import java.io.*
import java.lang.Integer.max
import java.lang.Math.abs
import java.util.*
import java.util.Comparator.comparing
import java.util.Comparator.comparingInt

/**
 * Help the Christmas elves fetch presents in a magical labyrinth!
 **/
fun main(args: Array<String>) {
    try {
//        val input = Scanner(System.`in`)
//        val input = Scanner(TeeInputStream(System.`in`, System.err))
        val input = Scanner(
            StringReader(
                """
                    0
                    1010 0101 1010 1001 0101 1001 1010
                    0101 1001 1010 1011 0111 0110 1110
                    1101 0110 0110 1010 0101 1001 0111
                    1011 0110 1101 1101 0110 1001 0101
                    1010 1110 1101 1110 1010 0101 1001
                    1010 1100 1011 1001 0111 0101 1010
                    1001 0110 1010 1010 0110 1010 0110
                    7 5 1 0111
                    11 5 3 0011
                    18
                    CANDY -1 -1 0
                    ARROW 2 3 0
                    SCROLL 4 4 0
                    SWORD 5 6 0
                    SWORD 6 0 1
                    ARROW 4 1 1
                    DIAMOND 3 1 1
                    CANDY 3 3 1
                    SCROLL 2 0 1
                    SHIELD 3 4 1
                    POTION 5 5 1
                    CANE 3 5 1
                    MASK 3 0 1
                    BOOK 1 3 1
                    SHIELD 2 5 0
                    POTION 1 0 0
                    FISH -2 -2 1
                    MASK 1 6 0
                    6
                    SWORD 0
                    MASK 0
                    CANDY 0
                    DIAMOND 1
                    FISH 1
                    SWORD 1
                """.trimIndent()
            )
        )

        // game loop
        while (true) {
            val turnType = input.nextInt() // 0 - push, 1 - move
            val board = Board(input)

            val we = Player(input)
            val enemy = Player(input)


            val items = (0 until input.nextInt()).map { Item(input) }
            val quests = (0 until input.nextInt()).map { Quest(input) }

            val ourQuests = quests.filter { it.questPlayerId == 0 }.map { it.questItemName }

            val gameBoard = GameBoard(
                board.board.mapIndexed { y, row ->
                    row.mapIndexed { x, cell ->
                        Field(
                            cell,
                            items.singleOrNull { it.itemX == x && it.itemY == y }
                        )
                    }
                })

            // Write an action using println()
            // To debug: System.err.println("Debug messages...");
            if (turnType == 0) {
                val fieldOnHand = Field(we.playerTile, items.singleOrNull { it.isOnOurHand })

                data class PushAndMove(val rowColumn: Int, val direction: Direction, val pathElem: PathElem)

                val pushes = (0..6).flatMap { rowColumn ->
                    Direction.values().flatMap { direction ->
                        val newBoard = gameBoard.push(fieldOnHand, direction, rowColumn)
                        val ourPoint = we.push(direction, rowColumn)
                        val paths = newBoard.findPaths(ourPoint, ourQuests)
                        paths.map { PushAndMove(rowColumn, direction, it) }
                    }
                }
                val comparator =
                    compareBy<PushAndMove> { it.pathElem.itemsTaken.size }.thenComparingInt { -it.pathElem.deep }
                val bestPush = pushes.maxWith(comparator)
                if (bestPush != null && bestPush.pathElem.itemsTaken.isNotEmpty()) {
                    println("PUSH ${bestPush.rowColumn} ${bestPush.direction}")
                } else {
                    val ourItems = items.filter { it.itemPlayerId == 0 && ourQuests.contains(it.itemName) }
                    val itemToMove = ourItems.minBy { listOf(it.itemX, 6 - it.itemX, it.itemY, 6 - it.itemY).min()!! }
                    if (itemToMove != null && !itemToMove.isOnHand) {
                        val direction = listOf(
                            itemToMove.itemX to LEFT,
                            (6 - itemToMove.itemX) to RIGHT,
                            itemToMove.itemY to UP,
                            (6 - itemToMove.itemY) to DOWN
                        ).minBy { it.first }!!.second

                        val rowCol = if (direction == UP || direction == DOWN) {
                            itemToMove.itemX
                        } else {
                            itemToMove.itemY
                        }

                        println("PUSH $rowCol $direction")
                    } else {

                        fun PushAndMove.point(): Point {
                            return when (this.direction) {
                                UP -> Point(this.rowColumn, 6)
                                DOWN -> Point(this.rowColumn, 0)
                                LEFT -> Point(6, this.rowColumn)
                                RIGHT -> Point(0, this.rowColumn)
                            }
                        }

                        val comparator =
                            compareBy<PushAndMove> { it.pathElem.deep }
                                .thenComparingInt {
                                    -abs(we.playerX - it.point().x) - abs(
                                        we.playerY - it.point().y
                                    )
                                }
                        val bestPush = pushes.maxWith(comparator)
                        if (bestPush != null) {
                            println("PUSH ${bestPush.rowColumn} ${bestPush.direction}")
                        } else {
                            println("PUSH ${max(0, we.playerY - 1)} $RIGHT")
                        }
                    }
                }
            } else {
                val paths = gameBoard.findPaths(we.point, ourQuests)
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
    val cell: Tile,
    val item: Item?
) {
    fun connect(field: Field, direction: Direction): Boolean {
        return this.cell.connect(field.cell, direction)
    }

}

data class PathElem(val point: Point, val itemsTaken: Set<String>) {
    var prev: PathElem? = null
    var direction: Direction? = null
    var deep: Int = 0
}

class GameBoard(board: List<List<Field>>) {
    val board = board.mapIndexed { y, row ->
        row.mapIndexed { x, field ->
            field.copy(item = field.item?.copy(itemX = x, itemY = y))
        }
    }


    fun findPaths(point: Point, quests: List<String>): List<PathElem> {
        fun PathElem.move(direction: Direction): PathElem {
            val newPoint = this.point.move(direction)
            val item = board[newPoint].item
            val newItems = if (item != null && item.itemPlayerId == 0 && quests.contains(item.itemName)) {
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

        val initialItem = board[point].item
        val initial =
            if (initialItem != null && initialItem.itemPlayerId == 0 && quests.contains(initialItem.itemName)) {
                PathElem(point, setOf(initialItem.itemName))
            } else {
                PathElem(point, emptySet())
            }

        val visited = mutableMapOf(initial to DOWN)

        var front = setOf<PathElem>(initial)

        repeat(20) {
            front = front.flatMap { pathElem ->
                Direction.values()
                    .filter { pathElem.point.can(it) }
                    .map { pathElem.move(it) to it }
                    .filterNot { (pathElem, _) -> visited.containsKey(pathElem) }
                    .map { (pathElem, direction) ->
                        visited[pathElem] = direction
                        pathElem
                    }
            }.toSet()
        }

        return visited.keys.toList()
    }

    fun push(field: Field, direction: Direction, rowColumn: Int): GameBoard {
        if (direction == LEFT || direction == RIGHT) {
            val mutableBoard = board.toMutableList()
            if (direction == LEFT) {
                mutableBoard[rowColumn] = board[rowColumn].drop(1) + listOf(field)
            } else {
                mutableBoard[rowColumn] = listOf(field) + board[rowColumn].dropLast(1)
            }
            return GameBoard(mutableBoard)
        } else {
            val board = board.transpose()
            val mutableBoard = board.toMutableList()
            if (direction == UP) {
                mutableBoard[rowColumn] = board[rowColumn].drop(1) + listOf(field)
            } else {
                mutableBoard[rowColumn] = listOf(field) + board[rowColumn].dropLast(1)
            }
            return GameBoard(mutableBoard.transpose())
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


class Player(input: Scanner) {
    val numPlayerCards = input.nextInt() // the total number of quests for a player (hidden and revealed)
    val playerX = input.nextInt()
    val playerY = input.nextInt()
    val playerTile = Tile.read(input)
    val point: Point = Point(playerX, playerY)

    fun push(direction: Direction, rowColumn: Int): Point {
        return if (direction == UP || direction == DOWN) {
            if (playerX != rowColumn) {
                this.point
            } else {
                val newPlayerY = (playerY + (if (direction == UP) -1 else 1) + 7) % 7
                Point(playerX, newPlayerY)
            }
        } else {
            if (playerY != rowColumn) {
                this.point
            } else {
                val newPlayerX = (playerX + (if (direction == LEFT) -1 else 1) + 7) % 7
                Point(newPlayerX, playerY)
            }
        }
    }
}

data class Item(val itemName: String, val itemX: Int, val itemY: Int, val itemPlayerId: Int) {
    constructor(input: Scanner) : this(
        itemName = input.next(),
        itemX = input.nextInt(),
        itemY = input.nextInt(),
        itemPlayerId = input.nextInt()
    )

    fun push(direction: Direction, rowColumn: Int): Point {
        return if (isOnHand) {
            when (direction) {
                UP -> Point(rowColumn, 6)
                DOWN -> Point(rowColumn, 0)
                LEFT -> Point(6, rowColumn)
                RIGHT -> Point(0, rowColumn)
            }
        } else {
            if (direction == UP || direction == DOWN) {
                if (itemX != rowColumn) {
                    this.point
                } else {
                    val newItemY = itemY + (if (direction == UP) -1 else 1)
                    if (newItemY >= 7 || newItemY < 0) {
                        Point(-1, -1)
                    } else {
                        Point(itemX, newItemY)
                    }
                }
            } else {
                if (itemY != rowColumn) {
                    this.point
                } else {
                    val newItemX = itemX + (if (direction == LEFT) -1 else 1)
                    if (newItemX >= 7 || newItemX < 0) {
                        Point(-1, -1)
                    } else {
                        Point(newItemX, itemY)
                    }
                }
            }
        }
    }

    val point: Point = Point(itemX, itemY)
    val isOnHand: Boolean = itemX < 0
    val isOnOurHand: Boolean = itemX == -1
    val isOnEnemyHand: Boolean = itemX == -2
}

class Quest(input: Scanner) {
    val questItemName = input.next()
    val questPlayerId = input.nextInt()
}

enum class Direction(val mask: Int) {
    UP(0b1000),
    RIGHT(0b0100),
    DOWN(0b0010),
    LEFT(0b0001);

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
