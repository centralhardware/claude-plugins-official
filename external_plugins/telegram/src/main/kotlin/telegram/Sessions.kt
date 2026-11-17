package telegram

import dev.inmo.tgbotapi.extensions.api.chat.forum.createForumTopic
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Named side sessions (/ns <name>): a separate `claude` in its own tmux session,
 * bound to one forum topic of the DM.
 *
 * Telegram allows one getUpdates consumer per token, so the main server keeps
 * polling for everyone. Inbound for a bound topic is not sent to the main
 * session but written as {method, params} files into sessions/<name>/; the side
 * session's own server runs in child mode (TELEGRAM_SESSION_NAME set): it never
 * polls, drains that spool into its MCP connection, and sends replies straight to
 * Telegram, defaulting to its topic.
 */
@Serializable
data class NamedSession(val name: String, val chatId: String, val threadId: Long, val tmux: String, val createdAt: Long)

object Sessions {
    private val file get() = File(Config.stateDir, "sessions.json")
    private val spoolRoot get() = File(Config.stateDir, "sessions")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    val NAME_RE = Regex("""^[a-z0-9][a-z0-9_-]{0,31}$""")

    /** Set in a side session's server: the name it serves. */
    val selfName: String? = System.getenv("TELEGRAM_SESSION_NAME")?.trim()?.ifBlank { null }
    val isChild get() = selfName != null

    fun load(): List<NamedSession> = synchronized(lock) {
        try { json.decodeFromString(ListSerializer(NamedSession.serializer()), file.readText()) } catch (_: Exception) { emptyList() }
    }

    private fun save(list: List<NamedSession>) = synchronized(lock) {
        val tmp = File(file.parentFile, "sessions.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(NamedSession.serializer()), list))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun byThread(chatId: String, threadId: Long?): NamedSession? =
        threadId?.let { t -> load().firstOrNull { it.chatId == chatId && it.threadId == t } }

    fun byName(name: String): NamedSession? = load().firstOrNull { it.name == name }

    fun self(): NamedSession? = selfName?.let { byName(it) }

    // ── Spool ────────────────────────────────────────────────────────────────

    /** Parent side: queue one MCP notification for the side session. */
    fun enqueue(s: NamedSession, method: String, params: JsonObject) {
        val dir = File(spoolRoot, s.name).apply { mkdirs() }
        val base = "%020d-%d".format(System.nanoTime(), ProcessHandle.current().pid())
        val tmp = File(dir, "$base.tmp")
        tmp.writeText(buildJsonObject { put("method", method); put("params", params) }.toString())
        Files.move(tmp.toPath(), File(dir, "$base.json").toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** Child side: hand every queued notification to [handle], oldest first, forever. */
    suspend fun drain(name: String, handle: suspend (String, JsonObject) -> Unit) {
        val dir = File(spoolRoot, name).apply { mkdirs() }
        while (currentCoroutineContext().isActive) {
            val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
            for (f in files) {
                try {
                    val o = Json.parseToJsonElement(f.readText()).jsonObject
                    handle(o["method"]!!.jsonPrimitive.content, o["params"]!!.jsonObject)
                } catch (e: Exception) {
                    log("spool ${f.name} not delivered: ${e.describe()}")
                }
                f.delete()
            }
            delay(300)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    private fun tmux(vararg args: String): Boolean = try {
        ProcessBuilder("tmux", *args).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
    } catch (e: Exception) {
        log("tmux ${args.firstOrNull()} failed: $e"); false
    }

    fun alive(s: NamedSession) = tmux("has-session", "-t", "=${s.tmux}")

    private fun sh(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Same command the watchdog launches the main session with. */
    private val launchCmd get() = Config.env("TELEGRAM_SESSION_LAUNCH_CMD")
        ?: "claude --dangerously-load-development-channels plugin:telegram@centralhardware-plugins"

    /** Startup prompts (dev channels, then trust) — the watchdog's default answers. */
    private val promptKeys get() = (Config.env("TELEGRAM_SESSION_PROMPT_KEYS") ?: "Down Enter;Enter")
        .split(';').map { it.trim().split(Regex("\\s+")).filter(String::isNotEmpty) }.filter { it.isNotEmpty() }

    /** How long a side session's server holds its spool after the MCP handshake: the prompts are answered 8 s apart. */
    val startupGraceMs get() = (promptKeys.size + 1) * 8000L

    /** Creates the topic, registers it, starts claude. Returns the session or an error text. */
    suspend fun spawn(scope: CoroutineScope, name: String, chatId: String): Result<NamedSession> = runCatching {
        require(NAME_RE.matches(name)) { "Name must be 1–32 chars: a-z, 0-9, - or _." }
        byName(name)?.let { if (alive(it)) error("Session \"$name\" already exists.") else remove(name) }
        val topic = Tg.bot.createForumTopic(chatIdOf(chatId), "🤖 $name", dev.inmo.tgbotapi.types.ForumTopic.CYAN)
        val s = NamedSession(name, chatId, topic.messageThreadId.long, "tg-$name", System.currentTimeMillis())
        save(load().filter { it.name != name } + s)
        File(spoolRoot, name).deleteRecursively()

        // tmux's -e isn't in every version; pass the env through the shell instead.
        val env = buildList {
            add("TELEGRAM_SESSION_NAME=$name")
            add("CLAUDE_TMUX_SESSION=${s.tmux}")
            Config.token?.let { add("TELEGRAM_BOT_TOKEN=$it") }
            System.getenv("CLAUDE_CONFIG_DIR")?.let { add("CLAUDE_CONFIG_DIR=$it") }
            System.getenv("PATH")?.let { add("PATH=$it") }
        }.joinToString(" ") { sh(it) }
        val home = System.getProperty("user.home")
        // The shell outlives claude, so a crash leaves the pane readable instead of vanishing.
        val cmd = "env $env $launchCmd; exec \$SHELL"
        check(tmux("new-session", "-d", "-s", s.tmux, "-c", home, cmd)) { "tmux new-session failed." }
        scope.launch {
            for (keys in promptKeys) {
                delay(8000)
                tmux("send-keys", "-t", s.tmux, *keys.toTypedArray())
            }
        }
        s
    }

    fun remove(name: String) {
        save(load().filter { it.name != name })
        File(spoolRoot, name).deleteRecursively()
    }

    /** Kills the tmux session and forgets the binding. */
    fun end(s: NamedSession) {
        tmux("kill-session", "-t", "=${s.tmux}")
        remove(s.name)
    }

    fun interrupt(s: NamedSession) = tmux("send-keys", "-t", s.tmux, "Escape")
}
