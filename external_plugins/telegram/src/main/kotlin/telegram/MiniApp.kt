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
                    get("/") { call.respondText(PAGE, ContentType.Text.Html) }
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

    private fun run(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) out else null
    } catch (_: Exception) { null }
}

private val PAGE = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>Claude session</title>
<script src="https://telegram.org/js/telegram-web-app.js"></script>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/css/xterm.min.css">
<script src="https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/lib/xterm.min.js"></script>
<style>
  html, body { margin: 0; height: 100%; background: #0d1117; color: #c9d1d9; font: 13px system-ui, sans-serif; }
  #bar { position: fixed; top: 0; left: 0; right: 0; height: 28px; display: flex; align-items: center;
         gap: 8px; padding: 0 12px; background: #161b22; border-bottom: 1px solid #30363d; z-index: 2; }
  #dot { width: 8px; height: 8px; border-radius: 50%; background: #6e7681; }
  #dot.live { background: #3fb950; }
  #status { flex: 1; color: #8b949e; }
  #term { position: absolute; top: 29px; left: 0; right: 0; bottom: 0; overflow: auto; }
  .xterm .xterm-viewport { overflow-y: hidden !important; }
</style>
</head>
<body>
<div id="bar"><span id="dot"></span><span id="status">connecting…</span></div>
<div id="term"></div>
<script>
const tg = window.Telegram && Telegram.WebApp;
if (tg) { tg.ready(); tg.expand(); }
const box = document.getElementById('term');
const statusEl = document.getElementById('status');
const dot = document.getElementById('dot');
let term = null, cols = 0, rows = 0, follow = true;

box.addEventListener('scroll', () => {
  follow = box.scrollTop + box.clientHeight >= box.scrollHeight - 8;
});

function setup(c, lines) {
  const fontSize = Math.max(6, Math.min(14, Math.floor(window.innerWidth / (c * 0.6))));
  if (!term || c !== cols) {
    if (term) term.dispose();
    term = new Terminal({ cols: c, rows: lines, fontSize, disableStdin: true, cursorBlink: false,
      scrollback: 0, convertEol: true, theme: { background: '#0d1117' } });
    term.open(box);
    cols = c;
  } else if (lines !== rows) {
    term.resize(c, lines);
  }
  rows = lines;
}

function render(msg) {
  const text = msg.data.replace(/\s+$/, '');
  const lines = Math.max(msg.rows, text.split('\n').length);
  setup(msg.cols, lines);
  term.reset();
  term.write(text);
  if (follow) box.scrollTop = box.scrollHeight;
  statusEl.textContent = 'live · ' + new Date(msg.ts).toLocaleTimeString();
}

function connect() {
  if (!tg || !tg.initData) { statusEl.textContent = 'open this from the bot in Telegram'; return; }
  const ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
  ws.onopen = () => ws.send(tg.initData);
  ws.onmessage = e => { dot.className = 'live'; render(JSON.parse(e.data)); };
  ws.onclose = e => {
    dot.className = '';
    if (e.code === 4401) { statusEl.textContent = 'not authorized'; return; }
    statusEl.textContent = 'reconnecting…';
    setTimeout(connect, 2000);
  };
}
connect();
</script>
</body>
</html>
""".trimIndent()
