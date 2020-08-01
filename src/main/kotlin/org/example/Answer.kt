import java.util.*
import java.io.*

/**
 * Help the Christmas elves fetch presents in a magical labyrinth!
 **/
fun main(args: Array<String>) {
    try {
        val input = Scanner(System.`in`)

        // game loop
        while (true) {
            val turnType = input.nextInt() // 0 - push, 1 - move
            val board = GameBoard(input)

            val we = Player(input)
            val enemy = Player(input)


            val items = (0 until input.nextInt()).map { Item(input) }
            val quests = (0 until input.nextInt()).map { Quest(input) }

            val ourQuest = quests.first { it.questPlayerId == 0 }
            val item = items.first { it.itemPlayerId == 0 && it.itemName == ourQuest.questItemName }

            // Write an action using println()
            // To debug: System.err.println("Debug messages...");
            if (turnType == 0) {
                val goodPushes = (0..6).flatMap { rowColumn ->
                    Direction.values().mapNotNull { direction ->
                        val newBoard = board.push(we.playerTile, direction, rowColumn)
                        val ourPoint = we.push(direction, rowColumn)
                        val itemPoint = item.push(direction, rowColumn)
                        val paths = newBoard.findPaths(ourPoint)
                        if (paths.containsKey(itemPoint)) {
                            rowColumn to direction
                        } else {
                            null
                        }
                    }
                }
                if (goodPushes.isNotEmpty()) {
                    val goodPush = goodPushes.first()
                    println("PUSH ${goodPush.first} ${goodPush.second}")
                } else if (item.isOnHand) {
                    println("PUSH 0 RIGHT")
                } else {
                    println("PUSH ${item.itemY} RIGHT")
                }
            } else {
                val paths = board.findPaths(we.point)
                if (paths.containsKey(item.point)) {
                    var directions = mutableListOf<Direction>()
                    var point = item.point
                    while (point != we.point) {
                        val direction = paths[point]!!
                        directions.add(0, direction)
                        point = point.move(direction.opposite)
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

class GameBoard(val board: List<List<String>>) {

    constructor(scanner: Scanner) : this(
        (0 until 7).map {
            (0 until 7).map {
                scanner.next()
            }
        }
    )

    fun findPaths(point: Point): MutableMap<Point, Direction> {
        val visited = mutableMapOf(point to Direction.DOWN)

        var front = setOf<Point>(point)

        (0 until 20).forEach {
            front = front.flatMap { point ->
                Direction.values()
                    .filter { point.can(it) }
                    .map { point.move(it) to it }
                    .filterNot { (point, _) -> visited.containsKey(point) }
                    .map { (point, direction) ->
                        visited[point] = direction
                        point
                    }
            }.toSet()
        }

        return visited
    }

    fun push(field: String, direction: Direction, rowColumn: Int): GameBoard {
        if (direction == Direction.LEFT || direction == Direction.RIGHT) {
            val mutableBoard = board.toMutableList()
            if (direction == Direction.LEFT) {
                mutableBoard[rowColumn] = board[rowColumn].drop(1) + listOf(field)
            } else {
                mutableBoard[rowColumn] = listOf(field) + board[rowColumn].dropLast(1)
            }
            return GameBoard(mutableBoard)
        } else {
            val board = board.transpose()
            val mutableBoard = board.toMutableList()
            if (direction == Direction.UP) {
                mutableBoard[rowColumn] = board[rowColumn].drop(1) + listOf(field)
            } else {
                mutableBoard[rowColumn] = listOf(field) + board[rowColumn].dropLast(1)
            }
            return GameBoard(mutableBoard.transpose())
        }
    }


    fun Point.can(direction: Direction) = when (direction) {
        Direction.UP -> canUp(this)
        Direction.DOWN -> canDown(this)
        Direction.LEFT -> canLeft(this)
        Direction.RIGHT -> canRight(this)
    }

    fun canUp(point: Point) = canUp(point.x, point.y)
    fun canRight(point: Point) = canRight(point.x, point.y)
    fun canDown(point: Point) = canDown(point.x, point.y)
    fun canLeft(point: Point) = canLeft(point.x, point.y)

    fun canUp(x: Int, y: Int) = (y > 0) && board[y][x][0] == '1' && board[y - 1][x][2] == '1'
    fun canRight(x: Int, y: Int) = (x < 6) && board[y][x][1] == '1' && board[y][x + 1][3] == '1'
    fun canDown(x: Int, y: Int) = (y < 6) && board[y][x][2] == '1' && board[y + 1][x][0] == '1'
    fun canLeft(x: Int, y: Int) = (x > 0) && board[y][x][3] == '1' && board[y][x - 1][1] == '1'
}

data class Point(val x: Int, val y: Int) {
    fun moveUp() = copy(y = y - 1)
    fun moveDown() = copy(y = y + 1)
    fun moveLeft() = copy(x = x - 1)
    fun moveRight() = copy(x = x + 1)

    fun move(direction: Direction) = when (direction) {
        Direction.UP -> moveUp()
        Direction.DOWN -> moveDown()
        Direction.LEFT -> moveLeft()
        Direction.RIGHT -> moveRight()
    }
}

class Player(input: Scanner) {
    val numPlayerCards = input.nextInt() // the total number of quests for a player (hidden and revealed)
    val playerX = input.nextInt()
    val playerY = input.nextInt()
    val playerTile = input.next() // our tile in hand 0010 - up|right|down|left
    val point: Point = Point(playerX, playerY)

    fun push(direction: Direction, rowColumn: Int): Point {
        return if (direction == Direction.UP || direction == Direction.DOWN) {
            if (playerX != rowColumn) {
                this.point
            } else {
                val newPlayerY = (playerY + (if (direction == Direction.UP) -1 else 1) + 7) % 7
                Point(playerX, newPlayerY)
            }
        } else {
            if (playerY != rowColumn) {
                this.point
            } else {
                val newPlayerX = (playerX + (if (direction == Direction.LEFT) -1 else 1) + 7) % 7
                Point(newPlayerX, playerY)
            }
        }
    }
}

class Item(input: Scanner) {
    fun push(direction: Direction, rowColumn: Int): Point {
        return if (isOnHand) {
            when (direction) {
                Direction.UP -> Point(rowColumn, 6)
                Direction.DOWN -> Point(rowColumn, 0)
                Direction.LEFT -> Point(6, rowColumn)
                Direction.RIGHT -> Point(0, rowColumn)
            }
        } else {
            if (direction == Direction.UP || direction == Direction.DOWN) {
                if (itemX != rowColumn) {
                    this.point
                } else {
                    val newItemY = itemY + (if (direction == Direction.UP) -1 else 1)
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
                    val newItemX = itemX + (if (direction == Direction.LEFT) -1 else 1)
                    if (newItemX >= 7 || newItemX < 0) {
                        Point(-1, -1)
                    } else {
                        Point(newItemX, itemY)
                    }
                }
            }
        }
    }

    val itemName = input.next()
    val itemX = input.nextInt()
    val itemY = input.nextInt()
    val itemPlayerId = input.nextInt()
    val point: Point = Point(itemX, itemY)
    val isOnHand: Boolean = itemX < 0
}

class Quest(input: Scanner) {
    val questItemName = input.next()
    val questPlayerId = input.nextInt()
}

enum class Direction() {
    UP,
    RIGHT,
    DOWN,
    LEFT;

    val opposite: Direction
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }
}

class TeeInputStream(val inputStream: InputStream) : InputStream() {
    override fun read(): Int {
        return inputStream.read()?.apply {
            if (this >= 0) {
                System.err.write(this)
            }
        }
    }

}

fun <T> List<List<T>>.transpose(): List<List<T>> {
    return (this[0].indices).map { x ->
        (this.indices).map { y ->
            this[y][x]
        }
    }
}

object Test {
    @JvmStatic
    fun main(args: Array<String>) {
        val original = GameBoard(
            listOf(
                listOf("00", "01"),
                listOf("10", "11")
            )
        )

        println("RIGHT = " + original.push("xx", Direction.RIGHT, 0).board)
        println("LEFT = " + original.push("xx", Direction.LEFT, 0).board)
        println("UP = " + original.push("xx", Direction.UP, 0).board)
        println("DOWN = " + original.push("xx", Direction.DOWN, 0).board)
    }
}
