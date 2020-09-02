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
        val replayId = "484747351"

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
        val sum: Array<LongArray> = Array(4) { LongArray(4) }
        fun printP(oe: Array<IntArray>, count: Int) {
            for (o in (0 until 3)) {
                for (e in (0 until 3)) {
                    print(oe[o][e] * 1.0 / count * 100)
                    print(" ")
                }
                println()
            }
            println("-------------------")
        }

        var replay = 0
        val map: MutableMap<Fingerprint, Pair<Array<IntArray>, Int>> = mutableMapOf()
        val threshold = 10_000_000
        File("replays").listFiles()
            .filter { it.extension == "txt" && !it.name.contains("raw") }
            .forEach { file ->
                val input = Scanner(file)
                val conditions = readInput(input)
                val board = conditions.gameBoard
                val fields: MutableList<BitField> = Point.points.flatten()
                    .map { p -> board.bitBoard[p] }
                    .toMutableList()
                fields.add(board.bitBoard.ourField())
                fields.add(board.bitBoard.enemyField())
                val footprint: List<Pair<Int, Int>> = fields.map {
                    it.tile.shl(1).or(if (it.item != 0) 1 else 0) to it
                }.groupBy { it.first }
                    .map { it.key to it.value.size }
                    .sortedBy { it.first }
                if(fingerPrints.add(footprint)) {
                    replay++
                    val vi = fields.filter { it.item != 0 }
                        .map { Integer.bitCount(it.tile.and(0b0101)) }.sum()
                    val hi = fields.filter { it.item != 0 }
                        .map { Integer.bitCount(it.tile.and(0b1010)) }.sum()
                    val v = fields.map { Integer.bitCount(it.tile.and(0b0101)) }.sum()
                    val h = fields.map { Integer.bitCount(it.tile.and(0b1010)) }.sum()

                    val key = if (vi < hi || (vi == hi && v < h)) {
                        Fingerprint(vi.toByte(), hi.toByte(), v.toByte(), h.toByte())
                    } else {
                        Fingerprint(hi.toByte(), vi.toByte(), h.toByte(), v.toByte())
                    }
                    println(footprint)
                    println(key)
                    val p = calculateProbabilities(fields, threshold)
                    for (o in (0 until 4)) {
                        for (e in (0 until 4)) {
                            sum[o][e] += p[o][e].toLong()
                        }
                    }
                    printP(p, threshold)
                    if (map[key] == null) {
                        map.put(key, Pair(p, 1))
                    } else {
                        val previous = map[key]!!
                        for (o in (0 until 4)) {
                            for (e in (0 until 4)) {
                                previous.first[o][e] += p[o][e]
                            }
                        }
                        map.put(key, Pair(previous.first, previous.second + 1))
                    }
                }
            }
        System.err.println(fingerPrints.size)
        map.entries
            .forEach {
                val count = threshold * it.value.second.toDouble()
                val f = it.value.first
                f[0][1] = (f[0][1] + f[1][0])/2
                f[0][2] = (f[0][2] + f[2][0])/2
                f[1][2] = (f[2][1] + f[1][2])/2
                val p00 = f[0][0] / count
                val p01 = f[0][1] / count
                val p02 = f[0][2] / count
                val p11 = f[1][1] / count
                val p12 = f[1][2] / count
                val p22 = f[2][2] / count
                println("${it.key} to doubleArrayOf($p00, $p01, $p02, $p11, $p12, $p22),")
            }
    }

    fun calculateProbabilities(fields: MutableList<BitField>, threshold: Int): Array<IntArray> {
        var count = 1
        val oe: Array<IntArray> = Array(4) { IntArray(4) }
        val quests = (0..11).toMutableList()
        while (count <= threshold) {
            fields.shuffle(rand)
            val rows = fields.subList(0, 49)
                .chunked(7)
                .map { row ->
                    var result = 0L
                    for (i in (0..6)) {
                        result = result.shl(9).or(row[i].bits)
                    }
                    result
                }.toLongArray()
            val hands = fields.subList(49, 51)
                .map { it.bits }
                .toLongArray()
            val rboard = GameBoard(BitBoard(rows, hands))

            val ourQuest = selectQuests(quests)
            val enemyQuest = selectQuests(quests)
            val ourD = rboard.findDomain(
                Point.point(rand.nextInt(7), rand.nextInt(7)),
                ourQuest,
                enemyQuest
            )
            val enemyD = rboard.findDomain(
                Point.point(rand.nextInt(7), rand.nextInt(7)),
                ourQuest,
                enemyQuest
            )
            oe[ourD.getOurQuestsCount()][enemyD.getEnemyQuestsCount()] += 1
            count++
        }
        return oe
    }

    private fun selectQuests(quests: MutableList<Int>): Int {
        quests.shuffle(rand)
        var ourQuest = 0
        quests.subList(0, 3).forEach {
            ourQuest = ourQuest.set(it)
        }
        return ourQuest
    }

}

object CountStop {
    @JvmStatic
    fun main(args: Array<String>) {
        File("replays").listFiles()
            .filter { it.extension == "yml" }
            .flatMap { file ->
                val firstLine = file.readLines().indexOfFirst { it.contains("step 1") }
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
//we counted we counted our little fingers were tired
private val fingerprints: Map<Fingerprint, DoubleArray> = mapOf(
    Fingerprint(20, 28, 58, 58) to doubleArrayOf(0.6700451, 0.1357889, 0.0114748, 0.0286995, 0.0026203, 2.825E-4),
    Fingerprint(20, 30, 48, 70) to doubleArrayOf(0.6290363, 0.1465372, 0.015452, 0.0358876, 0.0041706, 5.861E-4),
    Fingerprint(20, 30, 54, 62) to doubleArrayOf(0.6629998, 0.13738785, 0.01242025, 0.0297069, 0.0029437, 3.562E-4),
    Fingerprint(20, 32, 46, 70) to doubleArrayOf(0.6452038, 0.1419305, 0.0143452, 0.0326246, 0.0036147, 4.845E-4),
    Fingerprint(20, 32, 56, 60) to doubleArrayOf(0.65741895, 0.13827195, 0.01347795, 0.030467, 0.00325315, 4.2675E-4),
    Fingerprint(20, 34, 40, 76) to doubleArrayOf(0.6163811, 0.1491243, 0.0173068, 0.0378086, 0.0047769, 7.29E-4),
    Fingerprint(20, 34, 48, 68) to doubleArrayOf(0.6408474666666667, 0.14233548333333335, 0.0153769, 0.033075266666666665, 0.003925716666666666, 5.729833333333334E-4),
    Fingerprint(20, 34, 60, 56) to doubleArrayOf(0.6516658, 0.1390099, 0.0146605, 0.0311127, 0.0036358, 5.485E-4),
    Fingerprint(20, 34, 54, 62) to doubleArrayOf(0.65193242, 0.13929784, 0.01440842, 0.03108844, 0.00354232, 4.9858E-4),
    Fingerprint(20, 34, 54, 64) to doubleArrayOf(0.6334564, 0.1441465, 0.0160271, 0.0345681, 0.0042476, 6.594E-4),
    Fingerprint(20, 34, 56, 62) to doubleArrayOf(0.6296036, 0.1444801, 0.016955, 0.0348715, 0.0045613, 7.357E-4),
    Fingerprint(20, 36, 46, 70) to doubleArrayOf(0.63288805, 0.1439592, 0.0164914, 0.03435585, 0.0042984, 6.7145E-4),
    Fingerprint(20, 38, 50, 66) to doubleArrayOf(0.63470965, 0.14273675, 0.01698755, 0.0336259, 0.0043853, 7.0E-4),
    Fingerprint(22, 30, 52, 64) to doubleArrayOf(0.655929975, 0.1391401, 0.01323705, 0.0307943, 0.00322825, 4.12925E-4),
    Fingerprint(26, 30, 66, 52) to doubleArrayOf(0.6245804, 0.14517295, 0.0178184, 0.03561245, 0.00488665, 8.7345E-4),
    Fingerprint(28, 28, 50, 66) to doubleArrayOf(0.64273695, 0.14125965, 0.0156223, 0.03254585, 0.0039693, 6.0775E-4),
    Fingerprint(22, 34, 48, 70) to doubleArrayOf(0.6150316, 0.14833115, 0.0182309, 0.0375426, 0.00513765, 8.81E-4),
    Fingerprint(22, 32, 48, 70) to doubleArrayOf(0.6184187, 0.14790435, 0.0175434, 0.0371376, 0.00490315, 8.136E-4),
    Fingerprint(26, 32, 54, 62) to doubleArrayOf(0.6407812, 0.14130006, 0.0161872, 0.03267138, 0.00414966, 6.713E-4),
    Fingerprint(16, 36, 48, 68) to doubleArrayOf(0.6472251, 0.1410877, 0.0144303, 0.0322162, 0.0035551, 4.815E-4),
    Fingerprint(14, 38, 48, 70) to doubleArrayOf(0.6184417, 0.1478782, 0.0176362, 0.0371591, 0.0048637, 7.829E-4),
    Fingerprint(18, 34, 50, 66) to doubleArrayOf(0.6505801, 0.1402866, 0.0140369, 0.0315594, 0.0034533, 4.518E-4),
    Fingerprint(26, 26, 56, 60) to doubleArrayOf(0.6572497571428572, 0.1382773142857143, 0.013534485714285715, 0.030420342857142856, 0.0032633, 4.336E-4),
    Fingerprint(24, 28, 62, 54) to doubleArrayOf(0.6621677, 0.13789, 0.0121821, 0.0301409, 0.002909, 3.429E-4),
    Fingerprint(26, 28, 58, 58) to doubleArrayOf(0.6528102444444445, 0.13907762222222222, 0.014276655555555556, 0.030985055555555554, 0.0035166666666666666, 4.925888888888889E-4),
    Fingerprint(26, 26, 54, 62) to doubleArrayOf(0.65490485, 0.1391472, 0.01354965, 0.030903875, 0.003314175, 4.37825E-4),
    Fingerprint(24, 34, 52, 66) to doubleArrayOf(0.6150623, 0.1472294, 0.01906495, 0.037043, 0.00535805, 9.8505E-4),
    Fingerprint(26, 32, 60, 58) to doubleArrayOf(0.6234279, 0.1457379, 0.0177739, 0.0358159, 0.0048751, 8.547E-4),
    Fingerprint(26, 30, 62, 54) to doubleArrayOf(0.647002275, 0.14023805, 0.015160075, 0.03180335, 0.003793925, 5.78325E-4),
    Fingerprint(22, 34, 50, 68) to doubleArrayOf(0.6163888, 0.1482114, 0.0178828, 0.0375277, 0.0050209, 8.494E-4),
    Fingerprint(24, 28, 50, 66) to doubleArrayOf(0.6489639, 0.14070265, 0.01416295, 0.03193575, 0.0035322, 4.6975E-4),
    Fingerprint(24, 26, 64, 54) to doubleArrayOf(0.6390963, 0.14399865, 0.0143989, 0.03411155, 0.00378055, 5.2955E-4),
    Fingerprint(22, 34, 46, 70) to doubleArrayOf(0.6340542666666666, 0.1439593111111111, 0.016130266666666667, 0.03422031111111111, 0.004219644444444445, 6.438111111111111E-4),
    Fingerprint(16, 36, 50, 66) to doubleArrayOf(0.65415, 0.1394512, 0.01353735, 0.03116185, 0.00330595, 4.303E-4),
    Fingerprint(22, 34, 56, 60) to doubleArrayOf(0.6457431, 0.140173725, 0.01560245, 0.0318813, 0.00391305, 6.05375E-4),
    Fingerprint(28, 30, 50, 68) to doubleArrayOf(0.6154547, 0.1467881, 0.01929155, 0.03685075, 0.00538915, 0.0010143),
    Fingerprint(14, 38, 44, 72) to doubleArrayOf(0.6394053, 0.1435701, 0.0147927, 0.0338012, 0.0037895, 5.235E-4),
    Fingerprint(18, 36, 52, 64) to doubleArrayOf(0.6546075, 0.1389543, 0.0138366, 0.0308054, 0.0033824, 4.416E-4),
    Fingerprint(26, 28, 48, 68) to doubleArrayOf(0.6466166, 0.140997, 0.014617, 0.0322085, 0.0036805, 5.1905E-4),
    Fingerprint(26, 28, 52, 64) to doubleArrayOf(0.65103086, 0.13969318, 0.01430978, 0.03140504, 0.00354474, 5.0018E-4),
    Fingerprint(28, 30, 62, 54) to doubleArrayOf(0.6438185, 0.14050226, 0.01593354, 0.03210988, 0.00401846, 6.3842E-4),
    Fingerprint(26, 26, 56, 62) to doubleArrayOf(0.6366841, 0.1432428, 0.0158033, 0.0339367, 0.0041362, 6.268E-4),
    Fingerprint(16, 36, 50, 68) to doubleArrayOf(0.62477775, 0.14673445, 0.01661275, 0.03620515, 0.00452855, 7.151E-4),
    Fingerprint(26, 28, 54, 62) to doubleArrayOf(0.6524825, 0.13932045, 0.01414465, 0.03120305, 0.0034887, 4.7965E-4),
    Fingerprint(24, 32, 54, 62) to doubleArrayOf(0.647345175, 0.140059275, 0.015163575, 0.031814975, 0.00378385, 5.703E-4),
    Fingerprint(28, 30, 54, 62) to doubleArrayOf(0.64405465, 0.1408576, 0.0155435, 0.03228225, 0.00393615, 6.12E-4),
    Fingerprint(24, 26, 52, 66) to doubleArrayOf(0.6408079, 0.1434191, 0.0143324, 0.0337594, 0.0037434, 5.162E-4),
    Fingerprint(26, 28, 56, 60) to doubleArrayOf(0.6523776625, 0.1393009875, 0.0142234875, 0.031137475, 0.00349565, 4.95225E-4),
    Fingerprint(24, 32, 46, 70) to doubleArrayOf(0.6320673, 0.1443841, 0.016419066666666666, 0.0344711, 0.004313566666666667, 6.728666666666667E-4),
    Fingerprint(24, 32, 62, 54) to doubleArrayOf(0.64696255, 0.13984485, 0.0154814, 0.03164235, 0.0038821, 5.9445E-4),
    Fingerprint(24, 32, 48, 68) to doubleArrayOf(0.6395767, 0.1420391, 0.0160023, 0.0330593, 0.0040857, 6.201E-4),
    Fingerprint(28, 32, 62, 54) to doubleArrayOf(0.6412274, 0.1410386, 0.016277, 0.032477, 0.004153, 6.984E-4),
    Fingerprint(18, 38, 54, 62) to doubleArrayOf(0.639991, 0.1410359, 0.0167262, 0.0325614, 0.0042395, 6.951E-4),
    Fingerprint(24, 28, 52, 64) to doubleArrayOf(0.6576178, 0.1387978, 0.01299445, 0.03053655, 0.0031464, 3.9645E-4),
    Fingerprint(26, 28, 64, 52) to doubleArrayOf(0.6542516, 0.1388079, 0.0140375, 0.0308299, 0.003432, 4.7665E-4),
    Fingerprint(22, 30, 50, 66) to doubleArrayOf(0.65273012, 0.139765, 0.0137324, 0.03129796, 0.00337656, 4.4242E-4),
    Fingerprint(22, 34, 50, 66) to doubleArrayOf(0.6379881666666667, 0.14249391666666666, 0.016132383333333333, 0.03330825, 0.004151683333333333, 6.480333333333334E-4),
    Fingerprint(18, 32, 56, 62) to doubleArrayOf(0.6418167, 0.1425752, 0.0147352, 0.0333641, 0.003814, 5.376E-4),
    Fingerprint(24, 34, 62, 54) to doubleArrayOf(0.6408874, 0.1409313, 0.0164668, 0.0324336, 0.0042166, 6.979E-4),
    Fingerprint(22, 34, 60, 56) to doubleArrayOf(0.6476032, 0.13948413333333334, 0.0156067, 0.031459266666666666, 0.003882333333333333, 5.943666666666667E-4),
    Fingerprint(18, 34, 54, 62) to doubleArrayOf(0.65463315, 0.13884835, 0.0139139, 0.0308348, 0.0033678, 4.5675E-4),
    Fingerprint(24, 30, 52, 66) to doubleArrayOf(0.6276492333333333, 0.14538826666666665, 0.016796266666666667, 0.0354266, 0.0045462666666666665, 7.428166666666666E-4),
    Fingerprint(22, 36, 50, 66) to doubleArrayOf(0.63250765, 0.1432786, 0.01718665, 0.03399115, 0.0044939, 7.3275E-4),
    Fingerprint(28, 28, 58, 58) to doubleArrayOf(0.6458572333333333, 0.140179, 0.0155769, 0.03178386666666667, 0.0039248, 6.039666666666667E-4),
    Fingerprint(26, 28, 66, 50) to doubleArrayOf(0.6489849, 0.140029125, 0.014680525, 0.03168455, 0.003655375, 5.21675E-4),
    Fingerprint(22, 36, 56, 60) to doubleArrayOf(0.6380184, 0.1415656, 0.016891, 0.0328231, 0.0043237, 7.276E-4),
    Fingerprint(24, 32, 56, 60) to doubleArrayOf(0.64921355, 0.13965326666666666, 0.0149499, 0.031436783333333336, 0.0037128166666666666, 5.427833333333333E-4),
    Fingerprint(24, 32, 42, 74) to doubleArrayOf(0.626087, 0.1462604, 0.0167064, 0.0357074, 0.0044824, 6.943E-4),
    Fingerprint(28, 30, 58, 58) to doubleArrayOf(0.64259305, 0.1408303, 0.0160727, 0.03228365, 0.00407125, 6.4695E-4),
    Fingerprint(26, 26, 50, 66) to doubleArrayOf(0.6567325, 0.13882806666666667, 0.0132196, 0.030736933333333334, 0.0031934333333333335, 4.049E-4),
    Fingerprint(28, 30, 66, 50) to doubleArrayOf(0.6416516333333333, 0.14130906666666668, 0.015900533333333335, 0.03266466666666667, 0.004058733333333333, 6.462333333333333E-4),
    Fingerprint(18, 34, 54, 64) to doubleArrayOf(0.63470135, 0.1439961, 0.01575965, 0.034395, 0.0041856, 6.3935E-4),
    Fingerprint(20, 34, 50, 68) to doubleArrayOf(0.622624025, 0.14676285, 0.01719915, 0.03637495, 0.0047379, 7.72075E-4),
    Fingerprint(24, 30, 58, 58) to doubleArrayOf(0.653239475, 0.138941475, 0.014247925, 0.030928975, 0.00349985, 4.93925E-4),
    Fingerprint(22, 30, 56, 62) to doubleArrayOf(0.6352061, 0.1439918, 0.01559155, 0.03438415, 0.00413795, 6.3085E-4),
    Fingerprint(16, 36, 52, 66) to doubleArrayOf(0.6304544, 0.1447462, 0.0164676, 0.0350606, 0.0044067, 6.757E-4),
    Fingerprint(22, 32, 54, 64) to doubleArrayOf(0.6282059571428571, 0.145143, 0.0168234, 0.035294285714285716, 0.004543471428571429, 7.392285714285714E-4),
    Fingerprint(26, 30, 48, 68) to doubleArrayOf(0.6427831, 0.14184793333333334, 0.0151387, 0.03276143333333333, 0.0038521, 5.720666666666666E-4),
    Fingerprint(28, 28, 48, 68) to doubleArrayOf(0.6419287, 0.142024175, 0.0152423, 0.032896575, 0.003912875, 5.8225E-4),
    Fingerprint(24, 30, 52, 64) to doubleArrayOf(0.65032403, 0.13997476, 0.01431399, 0.03152381, 0.00355517, 4.9886E-4),
    Fingerprint(22, 32, 54, 62) to doubleArrayOf(0.65357585, 0.1392692, 0.0138649, 0.03102985, 0.00341105, 4.534E-4),
    Fingerprint(18, 36, 60, 56) to doubleArrayOf(0.6539631, 0.138484, 0.014439, 0.030645, 0.0035088, 4.874E-4),
    Fingerprint(18, 36, 46, 70) to doubleArrayOf(0.639583325, 0.14330115, 0.014982725, 0.03359405, 0.00384245, 5.4005E-4),
    Fingerprint(26, 32, 52, 64) to doubleArrayOf(0.6386746142857143, 0.14183604285714285, 0.016433985714285716, 0.03299211428571429, 0.0042209, 6.839428571428571E-4),
    Fingerprint(22, 30, 58, 58) to doubleArrayOf(0.664661, 0.1368021, 0.0123918, 0.029395, 0.0029059, 3.442E-4),
    Fingerprint(24, 30, 50, 66) to doubleArrayOf(0.6483604428571429, 0.14044151428571428, 0.0145516, 0.03185384285714286, 0.0036345285714285713, 5.120285714285714E-4),
    Fingerprint(24, 32, 52, 64) to doubleArrayOf(0.6430569285714286, 0.14110627142857143, 0.01568232857142857, 0.03243108571428571, 0.003965985714285714, 6.083428571428571E-4),
    Fingerprint(24, 30, 60, 58) to doubleArrayOf(0.6330364, 0.14413085, 0.01614, 0.0345562, 0.00432165, 6.85E-4),
    Fingerprint(28, 28, 56, 60) to doubleArrayOf(0.6474221272727273, 0.1399566909090909, 0.015234363636363636, 0.031723881818181816, 0.0038089454545454544, 5.748272727272727E-4),
    Fingerprint(24, 30, 54, 64) to doubleArrayOf(0.63044025, 0.14482965, 0.01639065, 0.03497925, 0.004404825, 7.138E-4),
    Fingerprint(26, 32, 48, 70) to doubleArrayOf(0.6120286, 0.1479794, 0.019328, 0.0375842, 0.0055007, 0.0010326),
    Fingerprint(22, 32, 60, 56) to doubleArrayOf(0.65243628, 0.1390324, 0.0144222, 0.03102966, 0.00355178, 5.0244E-4),
    Fingerprint(22, 36, 58, 58) to doubleArrayOf(0.6414125, 0.1404262, 0.0167465, 0.0322573, 0.0042393, 6.95E-4),
    Fingerprint(22, 36, 48, 68) to doubleArrayOf(0.6319667, 0.14366205, 0.01704205, 0.0341542, 0.00446915, 7.2875E-4),
    Fingerprint(26, 30, 54, 62) to doubleArrayOf(0.64773656, 0.1401957, 0.01494414, 0.03177386, 0.00374182, 5.5492E-4),
    Fingerprint(28, 32, 50, 66) to doubleArrayOf(0.6303536, 0.1438074, 0.017329, 0.0343195, 0.0046117, 8.271E-4),
    Fingerprint(26, 32, 66, 50) to doubleArrayOf(0.6414794, 0.140639, 0.0165633, 0.0322837, 0.0041837, 7.018E-4),
    Fingerprint(26, 28, 54, 64) to doubleArrayOf(0.62810834, 0.14519158, 0.0167916, 0.03532908, 0.00455238, 7.4474E-4),
    Fingerprint(22, 32, 56, 62) to doubleArrayOf(0.6358835, 0.1436565, 0.0156516, 0.0342193, 0.0041389, 6.352E-4),
    Fingerprint(14, 36, 44, 74) to doubleArrayOf(0.6121297, 0.1504709, 0.0174263, 0.0387887, 0.0049364, 7.472E-4),
    Fingerprint(22, 36, 56, 62) to doubleArrayOf(0.61686015, 0.1463795, 0.01923005, 0.0365684, 0.00534845, 0.00100355),
    Fingerprint(22, 34, 52, 66) to doubleArrayOf(0.6222587, 0.1463217, 0.017641425, 0.03621915, 0.004862575, 8.3775E-4),
    Fingerprint(26, 30, 58, 58) to doubleArrayOf(0.6493572375, 0.139780725, 0.0147660125, 0.0315291125, 0.0036795625, 5.370875E-4),
    Fingerprint(24, 28, 60, 56) to doubleArrayOf(0.6594198, 0.137875775, 0.013152525, 0.030161775, 0.0031589, 4.0395E-4),
    Fingerprint(26, 30, 54, 64) to doubleArrayOf(0.6245365, 0.14529573333333334, 0.01779675, 0.035611166666666666, 0.004843966666666667, 8.435833333333333E-4),
    Fingerprint(22, 34, 56, 62) to doubleArrayOf(0.6226972666666667, 0.1456143, 0.01810463333333333, 0.0358631, 0.004957433333333333, 8.643333333333334E-4),
    Fingerprint(22, 34, 58, 58) to doubleArrayOf(0.647244075, 0.1397658, 0.015471625, 0.0316013, 0.003871275, 5.91E-4),
    Fingerprint(26, 28, 50, 66) to doubleArrayOf(0.6494503333333334, 0.14019086666666666, 0.014421633333333333, 0.03165076666666667, 0.0035852666666666665, 5.018666666666667E-4),
    Fingerprint(18, 36, 42, 74) to doubleArrayOf(0.6189139, 0.1484866, 0.0170731, 0.0373518, 0.0046646, 7.045E-4),
    Fingerprint(22, 32, 50, 66) to doubleArrayOf(0.64670282, 0.14099214, 0.01463926, 0.03216224, 0.00367026, 5.1934E-4),
    Fingerprint(28, 30, 56, 62) to doubleArrayOf(0.6181186, 0.1459045, 0.0192499, 0.0362213, 0.0053377, 9.84E-4),
    Fingerprint(24, 32, 62, 56) to doubleArrayOf(0.6253964, 0.1449631, 0.01779085, 0.03537455, 0.00485165, 8.674E-4),
    Fingerprint(24, 30, 56, 60) to doubleArrayOf(0.6520763666666667, 0.13929648333333333, 0.014313616666666666, 0.03116275, 0.0035291333333333334, 4.961833333333334E-4),
    Fingerprint(26, 34, 48, 68) to doubleArrayOf(0.62750815, 0.1442617, 0.01793275, 0.03455975, 0.00477685, 8.3255E-4),
    Fingerprint(20, 32, 54, 62) to doubleArrayOf(0.657388625, 0.13856145, 0.013267425, 0.030478575, 0.00320565, 4.15075E-4),
    Fingerprint(28, 32, 64, 52) to doubleArrayOf(0.6358552, 0.1414965, 0.0175687, 0.0329951, 0.0045419, 7.872E-4),
    Fingerprint(24, 34, 48, 68) to doubleArrayOf(0.6309681, 0.1437061, 0.0172859, 0.0342767, 0.0045399, 7.495E-4),
    Fingerprint(28, 30, 58, 60) to doubleArrayOf(0.61976165, 0.145638725, 0.0189323, 0.03606265, 0.0052321, 9.635E-4),
    Fingerprint(22, 32, 58, 58) to doubleArrayOf(0.653274725, 0.138864525, 0.0143115, 0.03092055, 0.003502225, 4.90825E-4),
    Fingerprint(22, 26, 56, 60) to doubleArrayOf(0.6697013, 0.1361902, 0.0111641, 0.0289738, 0.0025987, 2.778E-4),
    Fingerprint(22, 30, 58, 60) to doubleArrayOf(0.6363113, 0.1434776, 0.0157137, 0.0340013, 0.0041396, 6.24E-4),
    Fingerprint(22, 30, 56, 60) to doubleArrayOf(0.65754875, 0.13838725, 0.0132949, 0.03049345, 0.0032349, 4.299E-4),
    Fingerprint(22, 30, 54, 62) to doubleArrayOf(0.6577602666666666, 0.1383992, 0.0132703, 0.0304497, 0.003198466666666667, 4.1183333333333333E-4),
    Fingerprint(22, 32, 46, 72) to doubleArrayOf(0.6180405, 0.1485808, 0.0171067, 0.0375668, 0.0047847, 7.55E-4),
    Fingerprint(16, 40, 36, 80) to doubleArrayOf(0.5899191, 0.1559244, 0.0194921, 0.0432372, 0.0059057, 0.0010019),
    Fingerprint(26, 30, 58, 60) to doubleArrayOf(0.6271191, 0.1447443, 0.01748755, 0.0351447, 0.0047216, 8.1085E-4),
    Fingerprint(28, 28, 54, 64) to doubleArrayOf(0.622915875, 0.14551225, 0.018062, 0.035897125, 0.004958825, 8.6825E-4),
    Fingerprint(24, 30, 54, 62) to doubleArrayOf(0.6523025, 0.13942518333333334, 0.014131883333333333, 0.0312377, 0.0034780833333333335, 4.7575E-4),
    Fingerprint(28, 30, 50, 66) to doubleArrayOf(0.6374827666666667, 0.1418868, 0.016737433333333333, 0.0331423, 0.0043229666666666665, 7.049333333333333E-4),
    Fingerprint(20, 36, 50, 66) to doubleArrayOf(0.6404632, 0.141909425, 0.015839675, 0.032909425, 0.004053425, 6.22025E-4),
    Fingerprint(26, 28, 60, 56) to doubleArrayOf(0.652586025, 0.1391627, 0.0142763, 0.031053975, 0.003508225, 4.97425E-4),
    Fingerprint(22, 32, 52, 66) to doubleArrayOf(0.627581275, 0.14544815, 0.0167575, 0.03544205, 0.0045612, 7.36375E-4),
    Fingerprint(26, 32, 56, 60) to doubleArrayOf(0.6432307, 0.14060635, 0.0160135, 0.03221475, 0.0040599, 6.4145E-4),
    Fingerprint(22, 36, 50, 68) to doubleArrayOf(0.6099252, 0.1484324, 0.0195862, 0.0380121, 0.0056, 0.0010426),
    Fingerprint(20, 34, 50, 66) to doubleArrayOf(0.6458037, 0.1411986, 0.014762266666666666, 0.032297733333333335, 0.0037117, 5.305E-4),
    Fingerprint(28, 30, 64, 52) to doubleArrayOf(0.6392083, 0.1414798, 0.01653505, 0.03285525, 0.0042425, 6.968E-4),
    Fingerprint(22, 32, 58, 60) to doubleArrayOf(0.6297295, 0.1447237, 0.0167489, 0.0348492, 0.0045011, 7.463E-4),
    Fingerprint(20, 36, 44, 74) to doubleArrayOf(0.6056189, 0.1511149, 0.0187693, 0.0396175, 0.0054187, 9.276E-4),
    Fingerprint(24, 30, 58, 60) to doubleArrayOf(0.630832925, 0.1441737, 0.016819475, 0.0347041, 0.004489675, 7.352E-4),
    Fingerprint(26, 30, 56, 62) to doubleArrayOf(0.62352976, 0.1456347, 0.0178228, 0.03583986, 0.004876, 8.4708E-4),
    Fingerprint(22, 32, 56, 60) to doubleArrayOf(0.6514036, 0.13932193333333334, 0.0145133, 0.031240666666666667, 0.0035775, 5.132666666666667E-4),
    Fingerprint(20, 32, 52, 64) to doubleArrayOf(0.65067205, 0.14007495, 0.0141554, 0.03159975, 0.00348325, 4.642E-4),
    Fingerprint(30, 30, 56, 62) to doubleArrayOf(0.6135336, 0.1463133, 0.0201814, 0.0367641, 0.005673, 0.0011418),
    Fingerprint(26, 30, 56, 60) to doubleArrayOf(0.6467444666666666, 0.1401171111111111, 0.015319677777777778, 0.031809566666666664, 0.0038409, 5.848555555555556E-4),
    Fingerprint(28, 32, 52, 64) to doubleArrayOf(0.6369311, 0.1419461, 0.016929, 0.0330149, 0.0043622, 7.188E-4),
    Fingerprint(24, 36, 50, 68) to doubleArrayOf(0.6016529, 0.1489538, 0.0215817, 0.0388747, 0.0062409, 0.0012822),
    Fingerprint(24, 32, 52, 66) to doubleArrayOf(0.6243208, 0.14586856666666667, 0.017392, 0.035884266666666664, 0.004752266666666667, 8.143333333333334E-4),
    Fingerprint(22, 32, 46, 70) to doubleArrayOf(0.6361078666666666, 0.14379203333333335, 0.015643066666666667, 0.0340347, 0.0040622666666666665, 5.936E-4),
    Fingerprint(24, 32, 50, 68) to doubleArrayOf(0.6187862, 0.1469508, 0.0182107, 0.0366079, 0.0050528, 8.999E-4),
    Fingerprint(20, 40, 50, 68) to doubleArrayOf(0.6041467, 0.1491091, 0.0207822, 0.0386847, 0.0059732, 0.0011948),
    Fingerprint(24, 30, 64, 54) to doubleArrayOf(0.6306449, 0.14439505, 0.01670425, 0.0347304, 0.00447735, 7.464E-4),
    Fingerprint(26, 32, 54, 64) to doubleArrayOf(0.6189174, 0.1460008, 0.0188841, 0.0363685, 0.005216, 9.612E-4),
    Fingerprint(22, 28, 56, 60) to doubleArrayOf(0.6695676, 0.1359199, 0.0114901, 0.028795, 0.0026668, 2.813E-4),
    Fingerprint(20, 36, 52, 64) to doubleArrayOf(0.6429035, 0.14120946666666667, 0.015617866666666667, 0.032569266666666666, 0.003959433333333333, 6.016333333333333E-4),
    Fingerprint(28, 28, 58, 60) to doubleArrayOf(0.6232737, 0.14542595, 0.0180544, 0.03572735, 0.0049535, 8.8235E-4),
    Fingerprint(26, 28, 56, 62) to doubleArrayOf(0.6328459, 0.1444151, 0.0159417, 0.03474575, 0.0042728, 6.748E-4),
    Fingerprint(16, 36, 46, 70) to doubleArrayOf(0.6415042, 0.1427225, 0.0148542, 0.0332549, 0.0037775, 5.404E-4),
    Fingerprint(30, 32, 68, 48) to doubleArrayOf(0.6293838, 0.1433803, 0.01796995, 0.0341421, 0.00477625, 8.7195E-4),
    Fingerprint(20, 36, 46, 72) to doubleArrayOf(0.6074574, 0.1500412, 0.0190412, 0.0389311, 0.0054979, 9.908E-4),
    Fingerprint(28, 28, 52, 64) to doubleArrayOf(0.64533544, 0.14059182, 0.01538308, 0.0320836, 0.00386494, 5.8526E-4),
    Fingerprint(22, 36, 52, 66) to doubleArrayOf(0.6160427, 0.1471548, 0.0187979, 0.0371277, 0.0052691, 9.738E-4),
    Fingerprint(22, 28, 52, 64) to doubleArrayOf(0.6616496, 0.1376707, 0.0125948, 0.0300118, 0.0029896, 3.582E-4),
    Fingerprint(24, 34, 58, 58) to doubleArrayOf(0.6464546, 0.1398791, 0.0156222, 0.0316767, 0.0039161, 5.966E-4),
    Fingerprint(22, 38, 48, 68) to doubleArrayOf(0.6152914, 0.1461245, 0.0200921, 0.0363811, 0.0054723, 0.0010432),
    Fingerprint(22, 32, 50, 68) to doubleArrayOf(0.6259964, 0.1462483, 0.0166476, 0.0359308, 0.0044978, 7.074E-4),
    Fingerprint(26, 32, 48, 68) to doubleArrayOf(0.63508876, 0.1429376, 0.01662388, 0.0337324, 0.00431784, 7.1112E-4),
    Fingerprint(28, 30, 38, 78) to doubleArrayOf(0.6157122, 0.1493834, 0.017122, 0.0380448, 0.0048414, 7.952E-4),
    Fingerprint(20, 28, 54, 62) to doubleArrayOf(0.6681472, 0.1364265, 0.0115406, 0.0290741, 0.0026776, 2.949E-4),
    Fingerprint(20, 32, 46, 72) to doubleArrayOf(0.6222071, 0.1477221, 0.0166216, 0.0367871, 0.0045374, 6.982E-4),
    Fingerprint(26, 28, 58, 60) to doubleArrayOf(0.6296821, 0.1444644, 0.0169361, 0.0349316, 0.0045204, 7.3985E-4),
    Fingerprint(22, 28, 60, 56) to doubleArrayOf(0.6641468, 0.1371156, 0.0122633, 0.0295325, 0.0029111, 3.385E-4),
    Fingerprint(16, 36, 40, 76) to doubleArrayOf(0.6190671, 0.14917475, 0.0164862, 0.03756375, 0.00453445, 6.4835E-4),
    Fingerprint(24, 34, 44, 72) to doubleArrayOf(0.6247784333333334, 0.1458218, 0.0174485, 0.03562113333333333, 0.004690033333333334, 7.682333333333334E-4),
    Fingerprint(16, 40, 48, 68) to doubleArrayOf(0.6336259, 0.1432232, 0.0169027, 0.0339304, 0.004382, 7.052E-4),
    Fingerprint(28, 32, 56, 60) to doubleArrayOf(0.6344459, 0.1418955, 0.0176405, 0.0333741, 0.0045778, 8.087E-4),
    Fingerprint(26, 32, 44, 72) to doubleArrayOf(0.6251197, 0.1455119, 0.01754325, 0.0355583, 0.00470705, 8.0445E-4),
    Fingerprint(24, 32, 50, 66) to doubleArrayOf(0.6411414444444444, 0.1418365888888889, 0.01565651111111111, 0.03287371111111111, 0.004002877777777778, 6.150888888888889E-4),
    Fingerprint(24, 30, 50, 68) to doubleArrayOf(0.6283072, 0.1453781, 0.01658445, 0.03542125, 0.0044905, 7.113E-4),
    Fingerprint(22, 34, 48, 68) to doubleArrayOf(0.63973885, 0.14241323333333333, 0.01566165, 0.03315233333333333, 0.0040112, 5.968166666666667E-4),
    Fingerprint(26, 30, 50, 66) to doubleArrayOf(0.6413244333333333, 0.1416401, 0.0157351, 0.0328117, 0.0040269, 6.183666666666667E-4),
    Fingerprint(20, 36, 48, 68) to doubleArrayOf(0.6368025166666667, 0.14292878333333334, 0.0161322, 0.0335978, 0.004168716666666667, 6.479166666666666E-4),
    Fingerprint(20, 34, 48, 70) to doubleArrayOf(0.6235799, 0.1472228, 0.0166021, 0.0364322, 0.0045381, 7.127E-4),
    Fingerprint(24, 32, 44, 72) to doubleArrayOf(0.6297539, 0.14498655, 0.01660375, 0.03499205, 0.0043994, 6.886E-4),
    Fingerprint(26, 30, 64, 52) to doubleArrayOf(0.6443862, 0.1406547, 0.0156286, 0.0321653, 0.0039482, 6.034E-4),
    Fingerprint(28, 28, 56, 62) to doubleArrayOf(0.627432, 0.14514393333333334, 0.0170261, 0.03533593333333333, 0.004638366666666667, 7.702E-4),
    Fingerprint(28, 30, 62, 56) to doubleArrayOf(0.6211977, 0.14594745, 0.0182445, 0.0360761, 0.00505695, 9.1495E-4),
    Fingerprint(18, 34, 58, 58) to doubleArrayOf(0.6601658, 0.1372848, 0.0133947, 0.0299411, 0.003187, 4.249E-4),
    Fingerprint(18, 36, 34, 82) to doubleArrayOf(0.5867134, 0.157155, 0.0194484, 0.0441379, 0.0059269, 9.467E-4),
    Fingerprint(16, 40, 46, 70) to doubleArrayOf(0.6260627, 0.1452907, 0.0175249, 0.0352662, 0.0046948, 7.725E-4),
    Fingerprint(22, 36, 46, 70) to doubleArrayOf(0.62525355, 0.14549555, 0.0175916, 0.0353457, 0.00472445, 7.9285E-4),
    Fingerprint(28, 30, 54, 64) to doubleArrayOf(0.6178577333333334, 0.14634513333333332, 0.018957366666666666, 0.03647673333333333, 0.0052549, 9.684666666666667E-4),
    Fingerprint(26, 26, 52, 64) to doubleArrayOf(0.6547482, 0.1391211, 0.0136508, 0.0308273, 0.0033383, 4.439E-4),
    Fingerprint(16, 36, 38, 78) to doubleArrayOf(0.6140478, 0.1504537, 0.0168984, 0.0386512, 0.0047287, 6.993E-4),
    Fingerprint(30, 30, 50, 66) to doubleArrayOf(0.63236035, 0.1425772, 0.01777945, 0.03364895, 0.00463925, 8.1365E-4),
    Fingerprint(20, 34, 46, 72) to doubleArrayOf(0.6139721, 0.14898325, 0.01799645, 0.03803485, 0.0050773, 8.4415E-4),
    Fingerprint(22, 30, 48, 68) to doubleArrayOf(0.6473529, 0.1412597, 0.0142432, 0.0321993, 0.003543, 4.776E-4),
    Fingerprint(28, 30, 60, 58) to doubleArrayOf(0.6239342, 0.1453509, 0.0179674, 0.0356216, 0.0048919, 8.472E-4),
    Fingerprint(12, 42, 44, 72) to doubleArrayOf(0.6285671, 0.1453425, 0.0167225, 0.0352388, 0.0044114, 6.888E-4),
    Fingerprint(16, 34, 46, 72) to doubleArrayOf(0.6246775, 0.147678, 0.0158915, 0.036621, 0.0043272, 6.223E-4),
    Fingerprint(26, 30, 50, 68) to doubleArrayOf(0.6229256666666667, 0.14620873333333334, 0.017513633333333334, 0.036156966666666665, 0.004823433333333333, 8.144666666666667E-4),
    Fingerprint(22, 36, 42, 74) to doubleArrayOf(0.6168847, 0.1479265, 0.0181053, 0.037112, 0.0050093, 8.586E-4),
    Fingerprint(28, 30, 56, 60) to doubleArrayOf(0.6412563, 0.141008275, 0.0162851, 0.03252925, 0.004152825, 6.82875E-4),
    Fingerprint(24, 30, 62, 56) to doubleArrayOf(0.6306205, 0.1444159, 0.0166858, 0.0347916, 0.004466, 7.3325E-4),
    Fingerprint(24, 30, 60, 56) to doubleArrayOf(0.65497735, 0.1385895, 0.01400465, 0.03067715, 0.0034066, 4.7E-4),
    Fingerprint(24, 36, 54, 62) to doubleArrayOf(0.6344836, 0.1422601, 0.017341, 0.0333641, 0.0045221, 8.091E-4),
    Fingerprint(22, 36, 48, 70) to doubleArrayOf(0.6041963, 0.1492175, 0.0206376, 0.0388135, 0.0059761, 0.0011277),
    Fingerprint(26, 30, 52, 64) to doubleArrayOf(0.6447733666666666, 0.14072, 0.015449866666666666, 0.032157483333333334, 0.0039025166666666667, 5.891E-4),
    Fingerprint(28, 28, 54, 62) to doubleArrayOf(0.64702605, 0.14034535, 0.015031, 0.03192275, 0.00376845, 5.6825E-4),
    Fingerprint(24, 32, 60, 56) to doubleArrayOf(0.6489432333333334, 0.13944233333333333, 0.015200866666666667, 0.0313796, 0.0037709333333333333, 5.704666666666667E-4),
    Fingerprint(28, 28, 48, 70) to doubleArrayOf(0.6197563, 0.1468117, 0.017981366666666665, 0.0366521, 0.004995566666666667, 8.628E-4),
    Fingerprint(18, 32, 52, 66) to doubleArrayOf(0.638604, 0.1436203, 0.0148781, 0.0339584, 0.0038983, 5.433E-4),
    Fingerprint(22, 30, 50, 68) to doubleArrayOf(0.6303603, 0.1452116, 0.0161317, 0.0351916, 0.0043057, 6.631E-4),
    Fingerprint(24, 30, 66, 50) to doubleArrayOf(0.6506254, 0.1393943, 0.0146943, 0.0313201, 0.0036288, 5.157E-4),
    Fingerprint(28, 30, 70, 46) to doubleArrayOf(0.6332078, 0.1432189, 0.0169626, 0.0339289, 0.0044541, 7.369E-4),
    Fingerprint(22, 30, 60, 58) to doubleArrayOf(0.6361906, 0.1435222, 0.0156795, 0.0341044, 0.0041572, 6.344E-4),
    Fingerprint(28, 32, 48, 68) to doubleArrayOf(0.6336137, 0.1430649, 0.016993, 0.0337845, 0.0044399, 7.451E-4),
    Fingerprint(22, 32, 52, 64) to doubleArrayOf(0.6486175166666667, 0.14035995, 0.014552916666666667, 0.03176916666666667, 0.0036242833333333334, 5.162833333333334E-4),
    Fingerprint(22, 30, 48, 70) to doubleArrayOf(0.6317765, 0.1456311, 0.0153467, 0.0353241, 0.0040989, 5.872E-4),
    Fingerprint(26, 34, 54, 64) to doubleArrayOf(0.6112722, 0.1472876, 0.0200651, 0.0373212, 0.0056958, 0.0011563),
    Fingerprint(26, 30, 46, 72) to doubleArrayOf(0.6138759, 0.1489594, 0.0180183, 0.0380436, 0.0051225, 8.733E-4),
    Fingerprint(26, 30, 52, 66) to doubleArrayOf(0.6191118666666666, 0.14663023333333333, 0.0183123, 0.0366111, 0.005086833333333333, 9.038E-4),
    Fingerprint(22, 36, 60, 58) to doubleArrayOf(0.6184101, 0.1456225, 0.0193927, 0.0360973, 0.0053354, 0.0010064),
    Fingerprint(26, 32, 50, 66) to doubleArrayOf(0.637192375, 0.142556625, 0.016280325, 0.0333855, 0.004234675, 6.79175E-4),
    Fingerprint(20, 32, 44, 74) to doubleArrayOf(0.6125459, 0.1504332, 0.01723, 0.0389, 0.0048876, 7.819E-4),
    Fingerprint(24, 32, 46, 72) to doubleArrayOf(0.61283315, 0.1491316, 0.0182097, 0.0381826, 0.00516295, 8.691E-4),
    Fingerprint(24, 34, 44, 74) to doubleArrayOf(0.600948, 0.1514193, 0.0198576, 0.0400525, 0.0058458, 0.0010622),
    Fingerprint(22, 34, 44, 72) to doubleArrayOf(0.6230358, 0.1464714, 0.0174872, 0.0360097, 0.004712, 7.514E-4),
    Fingerprint(24, 32, 58, 58) to doubleArrayOf(0.6480876, 0.14019795, 0.0147771, 0.0318711, 0.00370605, 5.5365E-4),
    Fingerprint(28, 32, 52, 66) to doubleArrayOf(0.6101076, 0.1475886, 0.0201783, 0.0375342, 0.0057603, 0.0011537),
    Fingerprint(28, 30, 48, 68) to doubleArrayOf(0.6379345, 0.141721, 0.0167449, 0.0330548, 0.0042824, 7.171E-4),
    Fingerprint(28, 28, 52, 66) to doubleArrayOf(0.62574265, 0.14555415, 0.01719845, 0.03562, 0.004713, 7.9685E-4),
    Fingerprint(24, 30, 44, 72) to doubleArrayOf(0.6413958, 0.1430542, 0.0145878, 0.0334122, 0.0037204, 5.128E-4),
    Fingerprint(26, 26, 58, 60) to doubleArrayOf(0.6366028, 0.1435301, 0.0155625, 0.0340156, 0.0041203, 6.154E-4),
    Fingerprint(28, 32, 68, 48) to doubleArrayOf(0.6368889, 0.1424242, 0.0164709, 0.0333999, 0.0042566, 7.145E-4),
    Fingerprint(24, 28, 58, 58) to doubleArrayOf(0.65890728, 0.13809446, 0.01314246, 0.03024632, 0.00316888, 4.073E-4),
    Fingerprint(14, 40, 40, 76) to doubleArrayOf(0.6129512, 0.1500109, 0.0177215, 0.0382526, 0.0049196, 7.638E-4),
    Fingerprint(22, 34, 46, 72) to doubleArrayOf(0.6136918, 0.1485727, 0.0183214, 0.0379387, 0.0051795, 9.189E-4),
    Fingerprint(18, 36, 40, 76) to doubleArrayOf(0.6224875, 0.1477458, 0.0165927, 0.036686, 0.0044889, 6.749E-4),
    Fingerprint(26, 28, 62, 54) to doubleArrayOf(0.6514918, 0.13930116, 0.01449278, 0.03124954, 0.0035665, 5.086E-4),
    Fingerprint(24, 28, 56, 60) to doubleArrayOf(0.6564804, 0.1381932, 0.0138427, 0.0304737, 0.0033493, 4.412E-4),
    Fingerprint(26, 30, 46, 70) to doubleArrayOf(0.6387967, 0.1426081, 0.0157561, 0.0333342, 0.0040564, 6.2425E-4),
    Fingerprint(22, 32, 64, 54) to doubleArrayOf(0.6373961, 0.1431449, 0.0156288, 0.0338606, 0.0041134, 6.167E-4),
    Fingerprint(20, 32, 58, 58) to doubleArrayOf(0.6584245, 0.137768, 0.0134992, 0.0303251, 0.0032658, 4.295E-4),
    Fingerprint(26, 32, 56, 62) to doubleArrayOf(0.6170937, 0.1463454, 0.0191634, 0.03656965, 0.00532025, 0.00100715),
    Fingerprint(26, 34, 58, 60) to doubleArrayOf(0.6170958, 0.1459482, 0.0195322, 0.0362428, 0.0054203, 0.0010433),
    Fingerprint(26, 28, 60, 58) to doubleArrayOf(0.6300346, 0.1444986, 0.0167748, 0.0349341, 0.0044989, 7.456E-4),
    Fingerprint(22, 30, 62, 56) to doubleArrayOf(0.6358796, 0.1435011, 0.0158056, 0.0341147, 0.0041808, 6.289E-4),
    Fingerprint(22, 36, 54, 62) to doubleArrayOf(0.63822685, 0.14158795, 0.01680925, 0.03287485, 0.0043019, 7.1165E-4),
    Fingerprint(18, 40, 40, 76) to doubleArrayOf(0.5954585, 0.152409, 0.020858, 0.040886, 0.0060602, 0.0010994),
    Fingerprint(16, 36, 44, 72) to doubleArrayOf(0.6405045, 0.14360635, 0.0144319, 0.0337189, 0.00368305, 4.9005E-4),
    Fingerprint(28, 32, 54, 62) to doubleArrayOf(0.6394117, 0.1411974, 0.0167384, 0.0327455, 0.0042492, 6.969E-4),
    Fingerprint(24, 26, 60, 56) to doubleArrayOf(0.6689733, 0.1363812, 0.0112834, 0.0290194, 0.0026136, 2.811E-4),
    Fingerprint(30, 30, 56, 60) to doubleArrayOf(0.6410208, 0.1408703, 0.0164908, 0.0324033, 0.004201, 7.005E-4),
    Fingerprint(20, 38, 52, 64) to doubleArrayOf(0.6384145, 0.1416622, 0.0167277, 0.0329022, 0.0042645, 6.944E-4),
    Fingerprint(20, 34, 42, 74) to doubleArrayOf(0.62902445, 0.14601855, 0.0159861, 0.0354353, 0.00427125, 6.3135E-4),
    Fingerprint(18, 36, 48, 68) to doubleArrayOf(0.6428386, 0.14216976, 0.0148802, 0.0328954, 0.00378186, 5.3558E-4),
    Fingerprint(26, 32, 58, 58) to doubleArrayOf(0.6395099, 0.14126775, 0.01657555, 0.0328177, 0.004259, 7.1145E-4),
    Fingerprint(18, 36, 46, 72) to doubleArrayOf(0.5992919, 0.1520718, 0.0198693, 0.0405075, 0.005839, 0.0010466),
    Fingerprint(22, 30, 54, 64) to doubleArrayOf(0.6352873, 0.14381435, 0.01575625, 0.03428925, 0.0041461, 6.2745E-4),
    Fingerprint(20, 32, 48, 70) to doubleArrayOf(0.6287443, 0.1464262, 0.015626, 0.0358842, 0.0042198, 6.051E-4),
    Fingerprint(24, 28, 60, 58) to doubleArrayOf(0.63610785, 0.14350165, 0.01576005, 0.03403375, 0.0041611, 6.326E-4),
    Fingerprint(20, 30, 56, 60) to doubleArrayOf(0.6632724, 0.1371927, 0.0125104, 0.0295957, 0.0029497, 3.599E-4),
    Fingerprint(26, 26, 58, 58) to doubleArrayOf(0.66024015, 0.13782145, 0.01295305, 0.0300559, 0.00308635, 3.8845E-4),
    Fingerprint(24, 32, 48, 70) to doubleArrayOf(0.6158031333333334, 0.14767043333333332, 0.018471366666666666, 0.03727253333333334, 0.0051959, 9.147E-4),
    Fingerprint(24, 34, 46, 70) to doubleArrayOf(0.6313827, 0.1436746, 0.0171203, 0.0344006, 0.0045138, 7.414E-4),
    Fingerprint(22, 36, 44, 72) to doubleArrayOf(0.6255134, 0.1459199, 0.0171714, 0.0356732, 0.0045772, 7.228E-4),
    Fingerprint(28, 34, 62, 56) to doubleArrayOf(0.6136833, 0.1461569, 0.0203051, 0.0366168, 0.0057061, 0.001133),
    Fingerprint(18, 34, 46, 70) to doubleArrayOf(0.6459629, 0.1423495, 0.0136602, 0.0328716, 0.0034808, 4.543E-4),
    Fingerprint(24, 30, 48, 70) to doubleArrayOf(0.6222823, 0.1470157, 0.0170453, 0.0365432, 0.0047212, 7.865E-4),
    Fingerprint(16, 34, 52, 64) to doubleArrayOf(0.659252, 0.138632, 0.0126035, 0.0304217, 0.0030259, 3.787E-4),
    Fingerprint(26, 30, 44, 74) to doubleArrayOf(0.6063066, 0.1505527, 0.0188833, 0.0394078, 0.0055011, 9.917E-4),
    Fingerprint(20, 32, 44, 72) to doubleArrayOf(0.64267675, 0.14320905, 0.01407415, 0.03334865, 0.0035799, 4.6635E-4),
    Fingerprint(24, 34, 56, 62) to doubleArrayOf(0.61741945, 0.14620515, 0.019176, 0.03647665, 0.00533565, 9.9705E-4),
    Fingerprint(20, 38, 42, 76) to doubleArrayOf(0.5884503, 0.1537442, 0.0215441, 0.0422, 0.0065261, 0.0012981),
    Fingerprint(20, 34, 44, 72) to doubleArrayOf(0.63510275, 0.14408925, 0.0156957, 0.03424645, 0.00409475, 6.0255E-4),
    Fingerprint(14, 38, 48, 68) to doubleArrayOf(0.6456557, 0.1415442, 0.0145159, 0.0324898, 0.0036504, 4.984E-4),
    Fingerprint(20, 38, 52, 66) to doubleArrayOf(0.6106615, 0.1479484, 0.0197582, 0.0377556, 0.0056242, 0.0010782),
    Fingerprint(28, 32, 60, 58) to doubleArrayOf(0.61331, 0.1459912, 0.0204997, 0.0367248, 0.0057657, 0.001161),
    Fingerprint(24, 28, 56, 62) to doubleArrayOf(0.6386317, 0.1433057, 0.01513745, 0.0338438, 0.00395145, 5.7365E-4),
    Fingerprint(24, 34, 58, 60) to doubleArrayOf(0.6183129, 0.1456017, 0.0193526, 0.0361686, 0.0053789, 0.0010316),
    Fingerprint(24, 30, 56, 62) to doubleArrayOf(0.6322854, 0.14413815, 0.01635305, 0.03467025, 0.00437405, 6.974E-4),
    Fingerprint(18, 36, 56, 60) to doubleArrayOf(0.6535792, 0.1388174, 0.014267, 0.0308138, 0.0034992, 4.895E-4),
    Fingerprint(28, 32, 58, 58) to doubleArrayOf(0.640165, 0.1409427, 0.0167088, 0.032449, 0.0042748, 7.081E-4),
    Fingerprint(26, 32, 50, 68) to doubleArrayOf(0.6123684, 0.14848225, 0.01887685, 0.03783635, 0.0053594, 9.6055E-4),
    Fingerprint(16, 38, 40, 78) to doubleArrayOf(0.590813, 0.1547598, 0.0200864, 0.0425952, 0.0060849, 0.0010753),
    Fingerprint(22, 28, 54, 62) to doubleArrayOf(0.6605407, 0.1378923, 0.0127824, 0.0301505, 0.0030347, 3.68E-4),
    Fingerprint(26, 34, 50, 66) to doubleArrayOf(0.6338656, 0.142825, 0.0171276, 0.0337198, 0.0044258, 7.481E-4),
    Fingerprint(16, 38, 44, 72) to doubleArrayOf(0.632682, 0.1446502, 0.0159684, 0.0346842, 0.0042015, 6.299E-4),
    Fingerprint(26, 28, 50, 68) to doubleArrayOf(0.6271203, 0.1453301, 0.0169544, 0.0355547, 0.0045938, 7.707E-4),
    Fingerprint(18, 36, 54, 62) to doubleArrayOf(0.6513083, 0.1393338, 0.014586, 0.0311485, 0.0035828, 5.165E-4),
    Fingerprint(22, 30, 66, 50) to doubleArrayOf(0.6553642, 0.1391242, 0.0133689, 0.0309748, 0.0032774, 4.355E-4),
    Fingerprint(26, 28, 52, 66) to doubleArrayOf(0.63320415, 0.14461215, 0.01569915, 0.0347807, 0.0042043, 6.3825E-4),
    Fingerprint(24, 34, 54, 64) to doubleArrayOf(0.6161337, 0.1465003, 0.0193253, 0.0366557, 0.005405, 0.0010227),
    Fingerprint(18, 36, 50, 66) to doubleArrayOf(0.6383512, 0.1427839, 0.015822, 0.0333586, 0.0040713, 6.131E-4),
    Fingerprint(26, 34, 52, 64) to doubleArrayOf(0.6377545, 0.141751, 0.0167508, 0.0329637, 0.0043581, 7.021E-4),
    Fingerprint(26, 36, 64, 52) to doubleArrayOf(0.631944, 0.1413975, 0.0188267, 0.0332395, 0.0048883, 9.29E-4),
    Fingerprint(28, 28, 50, 68) to doubleArrayOf(0.6255188, 0.1458279, 0.0171202, 0.0356948, 0.0046466, 7.584E-4),
    Fingerprint(16, 38, 50, 68) to doubleArrayOf(0.6180764, 0.1473064, 0.0181378, 0.0370493, 0.0050344, 8.586E-4),
    Fingerprint(18, 38, 50, 66) to doubleArrayOf(0.6366591, 0.1429667, 0.0162282, 0.0335471, 0.0041875, 6.306E-4),
    Fingerprint(16, 36, 42, 76) to doubleArrayOf(0.6069804, 0.1517308, 0.0178185, 0.0398959, 0.0051497, 8.402E-4),
    Fingerprint(28, 30, 64, 54) to doubleArrayOf(0.6166394, 0.1469627, 0.018735, 0.0368526, 0.0052909, 9.85E-4),
    Fingerprint(26, 30, 60, 56) to doubleArrayOf(0.6488471333333333, 0.1394281, 0.015236733333333334, 0.031420366666666665, 0.0037738333333333335, 5.739E-4),
    Fingerprint(18, 38, 46, 72) to doubleArrayOf(0.6042039, 0.1501982, 0.0198541, 0.0393012, 0.00579, 0.001085),
    Fingerprint(22, 34, 38, 78) to doubleArrayOf(0.6097439, 0.1505279, 0.0180763, 0.0389854, 0.0051202, 8.294E-4),
    Fingerprint(22, 34, 52, 64) to doubleArrayOf(0.64656865, 0.1407688, 0.0148396, 0.0321473, 0.0037165, 5.341E-4),
    Fingerprint(22, 28, 54, 64) to doubleArrayOf(0.638385, 0.1436371, 0.0149505, 0.0339737, 0.0039071, 5.558E-4),
    Fingerprint(24, 34, 50, 66) to doubleArrayOf(0.63602645, 0.14226975, 0.0168576, 0.03339395, 0.00437845, 7.197E-4),
    Fingerprint(24, 28, 48, 68) to doubleArrayOf(0.6474992, 0.14109815, 0.01428695, 0.0322486, 0.00355605, 4.7725E-4),
    Fingerprint(18, 34, 42, 76) to doubleArrayOf(0.611064, 0.151688, 0.0167508, 0.0393353, 0.004795, 7.073E-4),
    Fingerprint(20, 32, 40, 76) to doubleArrayOf(0.6269226, 0.1478984, 0.0150103, 0.0365447, 0.0040914, 5.767E-4),
    Fingerprint(30, 30, 54, 64) to doubleArrayOf(0.6179028, 0.14647345, 0.0188079, 0.03651295, 0.00523325, 9.72E-4),
    Fingerprint(28, 30, 52, 66) to doubleArrayOf(0.6167669, 0.1463471, 0.0192245, 0.0365888, 0.0053724, 0.0010232),
    Fingerprint(28, 30, 72, 46) to doubleArrayOf(0.613326, 0.1475644, 0.0192853, 0.0372934, 0.0054468, 0.0010313),
    Fingerprint(26, 30, 60, 58) to doubleArrayOf(0.62429205, 0.14528325, 0.0178421, 0.03568845, 0.0048747, 8.53E-4),
    Fingerprint(18, 32, 50, 66) to doubleArrayOf(0.6576991, 0.1388722, 0.0129218, 0.0306389, 0.003088, 3.787E-4),
    Fingerprint(22, 32, 62, 54) to doubleArrayOf(0.653453, 0.1386189, 0.0145086, 0.0306868, 0.0035338, 5.035E-4),
    Fingerprint(20, 34, 44, 74) to doubleArrayOf(0.5999183, 0.1523266, 0.0194788, 0.0405018, 0.0057579, 9.999E-4),
    Fingerprint(24, 28, 54, 62) to doubleArrayOf(0.6618429, 0.1375806, 0.0125964, 0.0300031, 0.0029849, 3.61E-4),
    Fingerprint(22, 28, 58, 58) to doubleArrayOf(0.6642678, 0.1368419, 0.0124603, 0.0294996, 0.0029142, 3.422E-4),
    Fingerprint(28, 30, 60, 56) to doubleArrayOf(0.6468093, 0.1398759, 0.0155075, 0.0316445, 0.0038902, 5.965E-4),
    Fingerprint(26, 32, 46, 70) to doubleArrayOf(0.6333702, 0.1432798, 0.0168484, 0.0339789, 0.0044211, 7.268E-4),
    Fingerprint(24, 38, 60, 56) to doubleArrayOf(0.6351445, 0.1412714, 0.0179727, 0.032936, 0.0046466, 8.483E-4),
    Fingerprint(22, 36, 36, 80) to doubleArrayOf(0.5960886, 0.153513, 0.019618, 0.0415248, 0.005801, 0.0010081),
    Fingerprint(24, 34, 50, 68) to doubleArrayOf(0.61659545, 0.14726095, 0.01861055, 0.0369286, 0.00520035, 9.1535E-4),
    Fingerprint(22, 28, 48, 68) to doubleArrayOf(0.6496472, 0.1413468, 0.0133635, 0.0323241, 0.0033082, 4.229E-4),
    Fingerprint(22, 36, 52, 64) to doubleArrayOf(0.633128, 0.1425501, 0.0175999, 0.0335821, 0.0045709, 7.873E-4),
    Fingerprint(24, 30, 44, 74) to doubleArrayOf(0.6188901, 0.1486873, 0.0167604, 0.0375919, 0.0046666, 7.133E-4),
    Fingerprint(22, 36, 54, 64) to doubleArrayOf(0.6138424, 0.1466904, 0.0198711, 0.0369572, 0.0055493, 0.0010523),
    Fingerprint(16, 34, 54, 62) to doubleArrayOf(0.6629258, 0.1371867, 0.0126304, 0.0296403, 0.0029861, 3.639E-4),
    Fingerprint(28, 32, 64, 54) to doubleArrayOf(0.6195631, 0.1456003, 0.0190414, 0.0359899, 0.0052394, 9.862E-4),
    Fingerprint(14, 40, 44, 72) to doubleArrayOf(0.6257953, 0.1460356, 0.0171021, 0.0355418, 0.0045151, 7.006E-4),
    Fingerprint(26, 30, 68, 48) to doubleArrayOf(0.6483568, 0.1403487, 0.014643, 0.0317942, 0.003637, 5.18E-4),
    Fingerprint(22, 30, 52, 66) to doubleArrayOf(0.6370877, 0.1447371, 0.0143457, 0.0346899, 0.0037954, 5.335E-4),
    Fingerprint(22, 34, 64, 54) to doubleArrayOf(0.6272785, 0.1442496, 0.0178056, 0.034986, 0.0048213, 8.393E-4),
    Fingerprint(22, 34, 54, 62) to doubleArrayOf(0.6408666, 0.1411266, 0.016372, 0.0325427, 0.0041372, 6.798E-4),
    Fingerprint(18, 32, 54, 62) to doubleArrayOf(0.6594656, 0.1379725, 0.0131178, 0.0301942, 0.0030932, 3.831E-4),
    Fingerprint(18, 38, 40, 76) to doubleArrayOf(0.615399, 0.1487268, 0.0179117, 0.037731, 0.0049393, 7.953E-4),
    Fingerprint(20, 36, 40, 76) to doubleArrayOf(0.61722585, 0.1487177, 0.01736405, 0.037527, 0.00479325, 7.4515E-4),
    Fingerprint(20, 34, 46, 70) to doubleArrayOf(0.6384657, 0.1431881, 0.0153546, 0.0336883, 0.0039729, 5.832E-4),
    Fingerprint(22, 34, 58, 60) to doubleArrayOf(0.6227362, 0.1454423, 0.018207, 0.0357679, 0.0050223, 8.964E-4),
    Fingerprint(16, 38, 48, 70) to doubleArrayOf(0.6124055, 0.1492877, 0.0181909, 0.0382908, 0.0051967, 8.79E-4),
    Fingerprint(26, 28, 64, 54) to doubleArrayOf(0.6307928, 0.1441292, 0.0168065, 0.0348031, 0.0045085, 7.442E-4),
    Fingerprint(22, 32, 48, 68) to doubleArrayOf(0.6434595, 0.14179035, 0.01498525, 0.03269735, 0.003804, 5.4695E-4),
    Fingerprint(18, 38, 44, 72) to doubleArrayOf(0.6260644, 0.1457603, 0.0171421, 0.035508, 0.0045696, 7.391E-4),
    Fingerprint(26, 30, 68, 50) to doubleArrayOf(0.6246032, 0.1460839, 0.0171387, 0.0360257, 0.004665, 7.71E-4),
    Fingerprint(24, 32, 54, 64) to doubleArrayOf(0.6237205, 0.1453571, 0.0179407, 0.0358124, 0.0049129, 8.619E-4),
    Fingerprint(26, 32, 58, 60) to doubleArrayOf(0.6190482, 0.1458578, 0.0190401, 0.0360353, 0.005269, 9.722E-4),
    Fingerprint(18, 36, 38, 78) to doubleArrayOf(0.6027649, 0.1523902, 0.0187279, 0.0403234, 0.0053996, 8.399E-4),
    Fingerprint(20, 36, 62, 54) to doubleArrayOf(0.6478151, 0.1395975, 0.0153983, 0.0316182, 0.003827, 5.951E-4),
    Fingerprint(22, 34, 62, 54) to doubleArrayOf(0.6534019, 0.1384729, 0.0145824, 0.0307797, 0.0035497, 5.196E-4),
    Fingerprint(26, 36, 40, 76) to doubleArrayOf(0.6017983, 0.1500132, 0.0207993, 0.0392045, 0.0060195, 0.0011462),
    Fingerprint(24, 34, 52, 64) to doubleArrayOf(0.6425185, 0.1416027, 0.0153853, 0.0327583, 0.0039324, 6.011E-4)
)