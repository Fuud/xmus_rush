package org.example

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import performGame
import java.io.File

object Replay {
    @JvmStatic
    fun main(args: Array<String>) {
        val replayId = "481360823"

        val replayFile = File("replays/$replayId.txt")

        val input = if (replayFile.exists()) {
            replayFile.readText()
        } else {
            val replayText = downloadReplay(replayId)
            replayFile.parentFile.mkdirs()
            replayFile.writeText(replayText)
            replayText
        }

        while (true){
            try {
                System.setIn(input.byteInputStream())
                performGame()
            }catch (t: Throwable){}
        }

    }

    private fun downloadReplay(replayId: String): String {
        val userId = 3871137
        val login = "fuudtorrentsru@gmail.com"
        val password = System.getProperty("password")!!

        val httpClient = HttpClient(Apache) {
            install(HttpCookies)

            install(JsonFeature) {
                serializer = JacksonSerializer()
            }
        }

        val input = runBlocking {
            // auth
            httpClient.post<Unit>("https://www.codingame.com/services/CodingamerRemoteService/loginSiteV2") {
                body = listOf(login, password, true)
                contentType(ContentType.Application.Json)
            }
            //get replay
            val gameInfo = httpClient.post<Game>("https://www.codingame.com/services/gameResult/findByGameId") {
                body = listOf(replayId, userId)
                contentType(ContentType.Application.Json)
            }

            val stdError = gameInfoToInput(gameInfo)
            File("replays/$replayId.raw.txt").apply {
                this.parentFile.mkdirs()
                this.writeText(stdError)
            }
            return@runBlocking stdError.lineSequence().filterNot { it.startsWith("#") }.joinToString(separator = "\n")
        }
        return input
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
    val view: String? = null,
    val keyframe: String? = null,
    val agentId: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Game(
    val frames: List<Frame>
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