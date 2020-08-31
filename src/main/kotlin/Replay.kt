import Replay.downloadReplay
import Replay.listLastBattles
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.cookies.HttpCookies
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*

val ymlMapper = ObjectMapper(YAMLFactory().apply {
    this.enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
}).apply {
    this.registerModule(KotlinModule())
    this.writerWithDefaultPrettyPrinter()
}

object Replay {
    val userId = 3871137
    val login = "fuudtorrentsru@gmail.com"
    val password = System.getProperty("password")!!

    val httpClient: HttpClient by lazy {
        HttpClient(Apache) {
            install(HttpCookies)

            install(JsonFeature) {
                serializer = JacksonSerializer()
            }
        }.apply {
            runBlocking {
                this@apply.post<kotlin.Unit>("https://www.codingame.com/services/CodingamerRemoteService/loginSiteV2") {
                    body = kotlin.collections.listOf(login, password, true)
                    contentType(io.ktor.http.ContentType.Application.Json)
                }
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val replayId = "484526092"

        val replayFile = File("replays/$replayId.txt")

        val input = if (replayFile.exists()) {
            replayFile.readText()
        } else {
            val replayText = downloadReplay(replayId)
            replayText
        }

        while (true) {
            try {
                System.setIn(input.byteInputStream())
                performGame()
            } catch (t: Throwable) {
                System.err.println(t)
            }
        }

    }

    fun downloadReplay(replayId: String): String {
        return runBlocking {
            //get replay
            val gameInfo = httpClient.post<Game>("https://www.codingame.com/services/gameResult/findByGameId") {
                body = listOf<Any>(replayId, userId)
                contentType(ContentType.Application.Json)
            }

            val names = gameInfo.agents.map { it.index to it.codingamer.pseudo }.toMap()

            val rendered = gameInfo.copy(frames = gameInfo.frames.map { it.copy(summary = it.summary?.replace("\$0", names[0]!!)?.replace("\$1", names[1]!!)) })

            val stdError = gameInfoToInput(gameInfo)
            File("replays/$replayId.raw.txt").apply {
                parentFile.mkdirs()
                writeText(stdError)
            }
            ymlMapper.writeValue(File("replays/$replayId.yml"), rendered)
            val filtered = stdError.lineSequence().filterNot { it.startsWith("#") }.joinToString(separator = "\n")
            File("replays/$replayId.txt").writeText(filtered)
            return@runBlocking filtered
        }
    }

    fun listLastBattles(sessionHandle: String): List<String> {
        return runBlocking {
            //get replay
            val gameInfo =
                httpClient.post<List<GameInfo>>("https://www.codingame.com/services/gamesPlayersRanking/findLastBattlesByTestSessionHandle") {
                    body = listOf(sessionHandle, null)
                    contentType(ContentType.Application.Json)
                }

            gameInfo.map { it.gameId }
        }
    }
}

private fun gameInfoToInput(gameInfo: Game): String {
    return gameInfo.frames
        .mapNotNull { it.stderr }
        .joinToString("\n")
        .lines()
        .joinToString("\n")
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Frame(
    val gameInformation: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val summary: String? = null,
//    val view: String? = null,
    val keyframe: String? = null,
    val agentId: String? = null
) {

}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Game(
    val frames: List<Frame>,
    val agents: List<Agent>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Agent(
    val index: Int,
    val codingamer: Codingamer
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Codingamer(
    val pseudo: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GameInfo(
    val gameId: String
)

object Transformer {
    @JvmStatic
    fun main(args: Array<String>) {
        val name = "1"
        val from = File("replays/$name.json")
        val to = File("replays/$name.txt")

        to.writeText(
            gameInfoToInput(
                jacksonObjectMapper().readValue<Game>(from)
            )
        )
    }
}

object DownloadLast {
    @JvmStatic
    fun main(args: Array<String>) {
        val games = listLastBattles("295709813627112ed42900bdc3f5ff26792c2c1b")
        games.forEach {
            downloadReplay(it)
        }
    }
}

object ComputeProbabilities {
    @JvmStatic
    fun main(args: Array<String>) {
        val fingerPrints = mutableSetOf<List<Pair<Int, Int>>>()
        val sum: Array<IntArray> = Array(4) { IntArray(4) }
        fun printP(oe : Array<IntArray>, count:Int) {
            for (o in (0 until 3)) {
                for (e in (0 until 3)) {
                    print(oe[o][e] * 1.0 / count * 100)
                    print(" ")
                }
                println()
            }
            println("-------------------")
        }
        var replay =0
        File("replays").listFiles()
            .filter { it.extension == "txt" && !it.name.contains("raw")}
            .filter { it.name.contains("484315152") }
            .forEach { file ->
                val input = Scanner(file)
                val conditions = readInput(input)
                val board = conditions.gameBoard
                val fields: MutableList<BitField> = Point.points.flatten()
                    .map { p -> board.bitBoard[p] }
                    .toMutableList()
                fields.add(board.bitBoard.ourField())
                fields.add(board.bitBoard.enemyField())
                val fingerPrint: List<Pair<Int, Int>> = fields.map {
                    it.tile.shl(1).or(if (it.item != 0) 1 else 0) to it
                }.groupBy { it.first }
                    .map { it.key to it.value.size }
                    .sortedBy { it.first }
                if(fingerPrints.add(fingerPrint)) {
                    replay++
                    System.err.println(fingerPrint)
                    val threshold = 10_000_000
                    val p = calculateProbabilities(fields, threshold)
                    for (o in (0 until 4)) {
                        for (e in (0 until 4)) {
                            sum[o][e] += p[o][e]
                        }
                    }
                    printP(sum, threshold * replay)
                }
            }
        System.err.println(fingerPrints.size)
    }
}

object CountStop {
    @JvmStatic
    fun main(args: Array<String>) {
        File("replays").listFiles()
            .filter { it.extension == "yml" }
            .flatMap { file ->
                val firstLine = file.readLines().indexOfFirst { it.contains("step 10") }
                if (firstLine < 0) {
                    emptyList<Pair<String, String>>()
                } else {
                    file.readLines()
                        .drop(firstLine)
                        .mapIndexed { index, line -> "${file.name}:${index + firstLine}" to line }
                        .filter { it.second.contains("stop computePushes") }
                        .map { (descr, line) -> descr to line }
                }
            }
            .sortedByDescending { it.second }
            .forEach {
                println(it)
            }
    }
}