import java.util.*
import java.io.*

/**
 * Help the Christmas elves fetch presents in a magical labyrinth!
 **/
fun main(args: Array<String>) {
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
            if (item.isOnHand){
                println("PUSH 0 RIGHT")
            }else {
                println("PUSH ${item.itemY} RIGHT")
            }
        } else {
            val paths = board.findPaths(we)
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
}

class GameBoard(input: Scanner) {
    val board = (0 until 7).map {
        (0 until 7).map {
            input.next()
        }
    }

    fun findPaths(player: Player): MutableMap<Point, Direction> {
        val visited = mutableMapOf<Point, Direction>(Point(player.playerX, player.playerY) to Direction.DOWN)

        var front = setOf<Point>(Point(player.playerX, player.playerY))

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
}

class Item(input: Scanner) {
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