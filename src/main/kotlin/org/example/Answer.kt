import Direction.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.util.*

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
                    0110 0110 0110 1110 1010 1101 1011
                    1011 1010 1111 1010 0011 1110 1001
                    1011 1010 0101 1001 0011 0111 0111
                    1101 0011 0101 1010 0101 1100 1110
                    0110 1101 1100 0110 0101 1010 1110
                    1101 1011 1100 1010 1111 1010 1001
                    0110 1110 0111 1010 1011 1001 1001
                    1 1 0 1001
                    1 6 6 0111
                    2
                    KEY -2 -2 0
                    KEY 0 5 1
                    2
                    KEY 0
                    KEY 1
                """.trimIndent()
            )
        )

        // game loop
        while (true) {
            System.err.println("S" + System.currentTimeMillis() % 1000)
            val turnType = input.nextInt() // 0 - push, 1 - move
            val board = Board(input)

            val we = Player(0, input)
            val enemy = Player(1, input)


            val items = (0 until input.nextInt()).map { Item(input) }
            val quests = (0 until input.nextInt()).map { Quest(input) }

            val ourQuests = quests.filter { it.questPlayerId == 0 }.map { it.questItemName }
            val enemyQuests = quests.filter { it.questPlayerId == 1 }.map { it.questItemName }

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
                val fieldOnEnemyHand = Field(enemy.playerTile, items.singleOrNull { it.isOnEnemyHand })

                data class PushAndMove(
                    val ourRowColumn: Int,
                    val ourDirection: Direction,
                    val enemyRowColumn: Int,
                    val enemyDirection: Direction,
                    val ourPaths: List<PathElem>,
                    val enemyPaths: List<PathElem>,
                    val ourDomain: DomainStat,
                    val enemyDomain: DomainStat
                )

                val pushes = (0..6).flatMap { rowColumn ->
                    Direction.values().flatMap { direction ->
                        (0..6).flatMap { enemyRowColumn ->
                            Direction.values().mapNotNull { enemyDirection ->
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
                                        Action(direction, rowColumn, fieldOnHand, isEnemy = false),
                                        Action(enemyDirection, enemyRowColumn, fieldOnEnemyHand, isEnemy = true)
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
                                            newTile = if (isEnemy) ourPlayer.playerTile else field.tile
                                        )
                                        enemyPlayer = enemyPlayer.push(
                                            direction,
                                            rowColumn,
                                            newTile = if (!isEnemy) enemyPlayer.playerTile else field.tile
                                        )
                                    }

//                                    val ourPaths = newBoard.findPaths(ourPlayer, ourQuests)
//                                    val enemyPaths = newBoard.findPaths(enemyPlayer, enemyQuests)

                                    val domains = newBoard.getDomainStats()
                                    PushAndMove(
                                        ourRowColumn = rowColumn,
                                        ourDirection = direction,
                                        enemyRowColumn = enemyRowColumn,
                                        enemyDirection = enemyDirection,
                                        ourPaths = listOf(),
                                        enemyPaths = listOf(),
                                        ourDomain = domains[ourPlayer.point]!!,
                                        enemyDomain = domains[enemyPlayer.point]!!
                                    )
                                }
                            }
                        }
                    }
                }
//                val comparator =
//                    compareBy<PushAndMove> { it.ourPaths.maxBy { it.itemsTaken.size }?.itemsTaken?.size ?: 0 }
//                val bestPush = pushes.maxWith(comparator)

                val (bestMove, bestScore) = pushes
                    .groupBy { it.ourDirection to it.ourRowColumn }
                    .maxBy { (_, pushesAndMoves) ->
                        val score = pushesAndMoves.map {
                            val enemyScore = it.enemyDomain.questCount(enemy, enemyQuests)
                            val ourScore = it.ourDomain.questCount(we, ourQuests)
                            ourScore - enemyScore
                        }.min()!!
                        score
                    }!!

                val (bestDirection, bestRowColumn) = bestMove

                println("PUSH $bestRowColumn $bestDirection")

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
            System.err.println("E" + System.currentTimeMillis() % 1000)
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

class DomainStat(var items: List<Item>, var size: Int) {
    fun questCount(player: Player, quests: List<String>): Int =
        items.filter { it.itemPlayerId == player.playerId && quests.contains(it.itemName) }.size

}

class GameBoard(board: List<List<Field>>) {
    val board = board.mapIndexed { y, row ->
        row.mapIndexed { x, field ->
            field.copy(item = field.item?.copy(itemX = x, itemY = y))
        }
    }

    fun getDomainStats(): Map<Point, DomainStat> {
        var id = 0
        val id2domain = mutableMapOf<Int, DomainStat>()
        val point2id = mutableMapOf<Point, Int>()
        for (x in (0..6)) {
            for (y in (0..6)) {
                val p = Point(x, y)
                val field = board[p]
                val left = p.moveLeft()
                val up = p.moveUp()
                val leftConnect = point2id.contains(left) && field.connect(board[left], LEFT)
                val upConnect = point2id.contains(up) && field.connect(board[up], UP)
                if (leftConnect && upConnect) {
                    val leftId = point2id[left]!!
                    val upId = point2id[up]!!
                    if (leftId == upId) {
                        val stat = id2domain[leftId]!!
                        stat.size++
                        if (field.item != null) {
                            stat.items += field.item
                        }
                    } else {
                        val leftStat = id2domain[leftId]!!
                        val upStat = id2domain[upId]!!
                        upStat.size += leftStat.size + 1
                        upStat.items += leftStat.items
                        id2domain[leftId] = upStat
                    }
                    point2id[p]=upId
                } else if (!leftConnect && !upConnect) {
                    id2domain[id] = DomainStat(listOfNotNull(field.item), 1)
                    point2id[p]=id
                    id++
                } else {
                    val connectedId = if (leftConnect) point2id[left]!! else point2id[up]!!
                    val stat = id2domain[connectedId]!!
                    stat.size++
                    if (field.item != null) {
                        stat.items += field.item
                    }
                    point2id[p]=connectedId
                }
            }
        }
        return point2id.mapValues { (_, id) -> id2domain[id]!! }
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

    fun push(field: Field, direction: Direction, rowColumn: Int): Pair<GameBoard, Field> {
        if (direction == LEFT || direction == RIGHT) {
            val mutableBoard = board.toMutableList()
            val field: Field
            if (direction == LEFT) {
                field = board[rowColumn][0]
                mutableBoard[rowColumn] = board[rowColumn].drop(1) + listOf(field)
            } else {
                field = board[rowColumn].last()
                mutableBoard[rowColumn] = listOf(field) + board[rowColumn].dropLast(1)
            }
            return GameBoard(mutableBoard) to field
        } else {
            val board = board.transpose()
            val mutableBoard = board.toMutableList()
            val field: Field
            if (direction == UP) {
                field = board[rowColumn][0]
                mutableBoard[rowColumn] = board[rowColumn].drop(1) + listOf(field)
            } else {
                field = board[rowColumn].last()
                mutableBoard[rowColumn] = listOf(field) + board[rowColumn].dropLast(1)
            }
            return GameBoard(mutableBoard.transpose()) to field
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
    val playerTile: Tile
) {

    constructor(playerId: Int, input: Scanner) : this(
        playerId = playerId,
        numPlayerCards = input.nextInt(), // the total number of quests for a player (hidden and revealed)
        playerX = input.nextInt(),
        playerY = input.nextInt(),
        playerTile = Tile.read(input)
    )

    val point: Point = Point(playerX, playerY)

    fun push(direction: Direction, rowColumn: Int, newTile: Tile): Player {
        return if (direction == UP || direction == DOWN) {
            if (playerX != rowColumn) {
                this
            } else {
                val newPlayerY = (playerY + (if (direction == UP) -1 else 1) + 7) % 7
                return copy(playerY = newPlayerY, playerTile = newTile)
            }
        } else {
            if (playerY != rowColumn) {
                this
            } else {
                val newPlayerX = (playerX + (if (direction == LEFT) -1 else 1) + 7) % 7
                return copy(playerX = newPlayerX, playerTile = newTile)
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
