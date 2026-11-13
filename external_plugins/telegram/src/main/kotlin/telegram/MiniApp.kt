package telegram

import dev.inmo.tgbotapi.extensions.api.chat.modify.setChatMenuButton
import dev.inmo.tgbotapi.types.MenuButton
import dev.inmo.tgbotapi.types.webapps.WebAppInfo
import dev.inmo.tgbotapi.utils.TelegramAPIUrlsKeeper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Watch-only Mini App: streams the Claude Code tmux pane (with scrollback) to
 * an xterm.js page opened from the bot's menu button. There is no input path —
 * the socket only ever sends frames to the client and ignores anything it
 * receives after authentication.
 *
 * Auth: the page sends Telegram's signed `initData` as its first socket
 * message. It must carry a valid hash (checked with ktgbotapi's
 * TelegramAPIUrlsKeeper), be at most a day old, and name a user in
 * access.allowFrom. Anything else closes the socket before a single frame.
 *
 * Config (env or .env): TELEGRAM_MINIAPP_PORT (default 8790; 0 disables),
 * TELEGRAM_MINIAPP_HOST (default 0.0.0.0), TELEGRAM_MINIAPP_URL (public https
 * URL; when set, allowlisted DMs get a menu button opening it),
 * TELEGRAM_MINIAPP_HISTORY (scrollback lines, default 3000).
 */
class MiniApp(token: String) {
    private val keeper = TelegramAPIUrlsKeeper(token)
    private val port = Config.env("TELEGRAM_MINIAPP_PORT")?.toIntOrNull() ?: 8790
    private val host = Config.env("TELEGRAM_MINIAPP_HOST") ?: "0.0.0.0"
    private val url = Config.env("TELEGRAM_MINIAPP_URL")?.takeIf { it.startsWith("https://") }
    private val history = Config.env("TELEGRAM_MINIAPP_HISTORY")?.toIntOrNull() ?: 3000
    private var server: EmbeddedServer<*, *>? = null

    /** Started only by the poller-lock holder, so instances never fight over the port. */
    fun start() {
        if (port <= 0 || server != null) return
        server = try {
            embeddedServer(CIO, port = port, host = host) {
                install(WebSockets) { pingPeriod = 20.seconds }
                routing {
                    get("/") { asset(call, "index.html", ContentType.Text.Html) }
                    get("/app.js") { asset(call, "app.js", ContentType.Text.JavaScript) }
                    get("/app.css") { asset(call, "app.css", ContentType.Text.CSS) }
                    webSocket("/ws") { stream(this) }
                }
            }.start(wait = false)
        } catch (e: Exception) {
            log("mini app server failed to start on $host:$port: ${e.message}")
            null
        }
        if (server != null) log("mini app listening on $host:$port${url?.let { " (public: $it)" } ?: ""}")
    }

    fun stop() {
        try { server?.stop(500, 1000) } catch (_: Exception) {}
    }

    /** Menu button "Session" for every allowlisted DM; no-op without a public URL. */
    suspend fun installMenuButton() {
        val u = url ?: return
        for (chatId in AccessStore.load().allowFrom) {
            try {
                Tg.bot.setChatMenuButton(chatIdOf(chatId), MenuButton.WebApp("Session", WebAppInfo(u)))
            } catch (e: Exception) { log("menu button for $chatId failed: ${e.describe()}") }
        }
    }

    /** Returns the user id when initData is genuine, fresh and allowlisted. */
    internal fun authorize(initData: String): String? {
        val params = initData.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), Charsets.UTF_8)
        }.toMap()
        val hash = params["hash"] ?: return null
        if (!keeper.checkWebAppData(initData, hash)) return null
        val authDate = params["auth_date"]?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() / 1000 - authDate > 86_400) return null
        val userId = try {
            Json.parseToJsonElement(params["user"] ?: return null).jsonObject["id"]?.jsonPrimitive?.longOrNull
        } catch (_: Exception) { null } ?: return null
        return userId.toString().takeIf { it in AccessStore.load().allowFrom }
    }

    private suspend fun stream(session: DefaultWebSocketServerSession) {
        val first = withTimeoutOrNull(10_000) { session.incoming.receive() } as? Frame.Text
        val user = first?.readText()?.let(::authorize)
        if (user == null) {
            session.close(CloseReason(4401, "unauthorized"))
            return
        }
        log("mini app viewer connected: $user")
        // Drain and ignore anything else the client sends — this view takes no input.
        val drain = session.launch { for (f in session.incoming) { /* ignored */ } }
        var last = ""
        try {
            while (session.isActive) {
                val frame = capture()
                if (frame != last) {
                    session.send(Frame.Text(frame))
                    last = frame
                }
                delay(1000)
            }
        } catch (_: Exception) {
        } finally {
            drain.cancel()
        }
    }

    /** One JSON frame: pane size plus the ANSI-coloured pane with scrollback. */
    private fun capture(): String {
        val target = Config.tmuxSession
        val size = run("tmux", "display-message", "-p", "-t", target, "#{pane_width} #{pane_height}")
            ?.trim()?.split(' ')?.mapNotNull { it.toIntOrNull() }
        val text = run("tmux", "capture-pane", "-p", "-e", "-J", "-S", "-$history", "-t", target)
        return json(
            "cols" to (size?.getOrNull(0) ?: 80),
            "rows" to (size?.getOrNull(1) ?: 24),
            "data" to (text ?: "tmux session \"$target\" is not running"),
            "ts" to System.currentTimeMillis(),
        ).toString()
    }

    /** Frontend files live in src/main/resources/miniapp/ and ship inside the jar. */
    private suspend fun asset(call: ApplicationCall, name: String, type: ContentType) {
        val bytes = assets.getOrPut(name) {
            MiniApp::class.java.getResourceAsStream("/miniapp/$name")?.use { it.readBytes() } ?: ByteArray(0)
        }
        if (bytes.isEmpty()) call.respond(HttpStatusCode.NotFound) else call.respondBytes(bytes, type)
    }
    private val assets = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private fun run(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) out else null
    } catch (_: Exception) { null }
}
