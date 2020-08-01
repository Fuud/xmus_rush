import java.util.*
import java.io.*
import java.math.*

/**
 * Help the Christmas elves fetch presents in a magical labyrinth!
 **/
fun main(args : Array<String>) {
    val input = Scanner(System.`in`)

    // game loop
    while (true) {
        val turnType = input.nextInt()
        for (i in 0 until 7) {
            for (j in 0 until 7) {
                val tile = input.next()
            }
        }
        for (i in 0 until 2) {
            val numPlayerCards = input.nextInt() // the total number of quests for a player (hidden and revealed)
            val playerX = input.nextInt()
            val playerY = input.nextInt()
            val playerTile = input.next()
        }
        val numItems = input.nextInt() // the total number of items available on board and on player tiles
        for (i in 0 until numItems) {
            val itemName = input.next()
            val itemX = input.nextInt()
            val itemY = input.nextInt()
            val itemPlayerId = input.nextInt()
        }
        val numQuests = input.nextInt() // the total number of revealed quests for both players
        for (i in 0 until numQuests) {
            val questItemName = input.next()
            val questPlayerId = input.nextInt()
        }

        // Write an action using println()
        // To debug: System.err.println("Debug messages...");

        println("PUSH 3 RIGHT") // PUSH <id> <direction> | MOVE <direction> | PASS
    }
}