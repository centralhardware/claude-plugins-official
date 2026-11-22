package telegram

import dev.inmo.tgbotapi.extensions.api.chat.forum.createForumTopic
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.api.send.setMessageReaction
import dev.inmo.tgbotapi.types.reactions.Reaction
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
        val p = ProcessBuilder("tmux", *args).redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        (p.waitFor() == 0).also { ok ->
            // has-session failing is the normal "not alive" answer, not an error.
            if (!ok && args.firstOrNull() != "has-session") log("tmux ${args.firstOrNull()} exited ${p.exitValue()}: $out")
        }
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
        launch(scope, s)
        s
    }

    /**
     * Relaunches claude for an existing binding in the same topic — for a session
     * whose Telegram server never came up. Undelivered spooled messages are kept.
     */
    fun restart(scope: CoroutineScope, s: NamedSession): Result<NamedSession> = runCatching {
        tmux("kill-session", "-t", "=${s.tmux}")
        aliveMarker(s.name).delete()
        val fresh = s.copy(createdAt = System.currentTimeMillis())
        save(load().filter { it.name != s.name } + fresh)
        launch(scope, fresh, clearSpool = false)
        fresh
    }

    private fun launch(scope: CoroutineScope, s: NamedSession, clearSpool: Boolean = true) {
        val name = s.name
        if (clearSpool) File(spoolRoot, name).deleteRecursively()

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
        scope.launch { answerPrompts(s.tmux) }
        scope.launch { checkAlive(s) }
    }

    // ── Liveness ─────────────────────────────────────────────────────────────

    private fun aliveMarker(name: String) = File(File(spoolRoot, name), ".alive")

    /** Child side: its server is up and past the handshake. */
    fun markAlive(name: String) = try { aliveMarker(name).apply { parentFile.mkdirs() }.writeText(System.currentTimeMillis().toString()) }
        catch (e: Exception) { log("alive marker not written: ${e.describe()}") }

    /** Child side: 👀 on the first delivered message, or a short line when there is none to react to. */
    suspend fun signalAlive(chatId: String, threadId: Long?, messageId: Long?) {
        try {
            if (messageId != null) Tg.bot.setMessageReaction(chatIdOf(chatId), msgIdOf(messageId), Reaction.Emoji("👀"))
            else Tg.bot.sendMessage(chatIdOf(chatId), "👀 Got it.", threadId = threadOf(threadId))
        } catch (e: Exception) {
            log("sign of life failed: ${e.describe()}")
            try { Tg.bot.sendMessage(chatIdOf(chatId), "👀 Got it.", threadId = threadOf(threadId)) } catch (_: Exception) {}
        }
    }

    /** Parent side: hands a notification to the main session (set once its MCP server exists). */
    @Volatile var notifyMain: ((String, JsonObject) -> Unit)? = null

    private fun tellMain(s: NamedSession, event: String, content: String) {
        val meta = buildJsonObject {
            put("chat_id", s.chatId)
            mainThread(s.chatId)?.let { put("thread_id", it.toString()) }
            put("user", "system"); put("event", event); put("session", s.name)
            put("ts", java.time.Instant.now().toString())
        }
        try { notifyMain?.invoke("notifications/claude/channel", buildJsonObject { put("content", content); put("meta", meta) }) }
        catch (e: Exception) { log("main not told about ${s.name}: ${e.describe()}") }
    }

    /**
     * Parent side: relays the child's check-in to the main session as session_alive;
     * when none arrives, Main gets session_dead instead. A side session whose Telegram server never came up (e.g. Claude
     * Code skipped it on a cached MCP failure) is deaf: say so in its topic and ask
     * Main to restart it.
     */
    private suspend fun checkAlive(s: NamedSession) {
        val deadline = System.currentTimeMillis() + startupGraceMs + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (byName(s.name)?.createdAt != s.createdAt) return
            // The side session checked in: pass it on to Main — its arrival is the proof of life.
            if (aliveMarker(s.name).exists()) {
                tellMain(s, "session_alive", "[side session \"${s.name}\" checked in: it is alive and connected.]")
                return
            }
            delay(2000)
        }
        val current = byName(s.name)?.takeIf { it.createdAt == s.createdAt } ?: return
        if (aliveMarker(s.name).exists()) return
        val why = if (alive(current)) "its Telegram server never connected, so messages in its topic aren't reaching it"
            else "its tmux session is gone"
        log("side session \"${s.name}\" shows no sign of life: $why")
        try {
            Tg.bot.sendMessage(chatIdOf(s.chatId), "⚠️ Session \"${s.name}\" didn't come up: $why. Main is looking into it.",
                threadId = threadOf(s.threadId))
        } catch (e: Exception) { log("liveness warning failed: ${e.describe()}") }
        tellMain(s, "session_dead", "[side session \"${s.name}\" never checked in: $why. Find out what happened: " +
            "read its pane (tmux capture-pane -p -t ${s.tmux}) and the plugin log, fix the cause if you can, then call restart_session " +
            "with name \"${s.name}\". Tell the user in Main what you found and whether it came back.]")
    }

    // ── Startup screen ───────────────────────────────────────────────────────

    /** The visible pane text, or null when tmux can't be read. */
    fun paneText(target: String): String? = try {
        val p = ProcessBuilder("tmux", "capture-pane", "-p", "-t", target).redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) out else null
    } catch (e: Exception) { null }

    /** A startup dialog (dev channels, trust) waiting for a choice. */
    private fun isDialog(pane: String) = pane.contains("Enter to confirm")

    /** The folder-trust dialog: "No, exit" is listed first, "Yes, I trust this folder" second. */
    private fun isTrustDialog(pane: String) =
        Regex("trust this folder|Yes, I trust|Do you trust", RegexOption.IGNORE_CASE).containsMatchIn(pane)

    /** The input box is up and no dialog covers it: Claude Code can take a message. */
    fun isReady(pane: String): Boolean {
        if (isDialog(pane)) return false
        // The prompt line sits between two full-width rules; a dialog's "❯ 1. Yes" cursor doesn't.
        val l = pane.lines().map { it.trimStart() }
        return l.indices.any { i ->
            l[i].startsWith("❯") && l.getOrNull(i - 1)?.startsWith("───") == true && l.getOrNull(i + 1)?.startsWith("───") == true
        }
    }

    /**
     * Answers each startup dialog the moment it is on screen instead of after a
     * fixed 8 s, falling back to the old blind timing if the pane can't be read.
     */
    private suspend fun answerPrompts(target: String) {
        val deadline = System.currentTimeMillis() + startupGraceMs
        var next = 0
        var lastAnswered: String? = null
        var lastAction = System.currentTimeMillis()
        fun answer() { tmux("send-keys", "-t", target, *promptKeys[next++].toTypedArray()); lastAction = System.currentTimeMillis() }
        while (next < promptKeys.size && System.currentTimeMillis() < deadline) {
            val pane = paneText(target)
            when {
                pane != null && isReady(pane) -> return
                // Answer by what the dialog is, not by its position: the trust prompt
                // doesn't always come second, and a blind "Down Enter" picks "Exit" on either.
                pane != null && isDialog(pane) && pane != lastAnswered -> {
                    // Park the cursor on the accepting option whatever its start: it is
                    // last in the trust dialog ("No, exit" / "Yes, I trust") and first in
                    // the dev-channels one ("I am using this…" / "Exit").
                    val keys = when {
                        isTrustDialog(pane) -> listOf("Down", "Down", "Enter")
                        pane.contains("I am using this for local development") -> listOf("Up", "Up", "Enter")
                        else -> null
                    }
                    if (keys != null) { tmux("send-keys", "-t", target, *keys.toTypedArray()); lastAction = System.currentTimeMillis() }
                    else answer()
                    lastAnswered = pane
                }
                // Unrecognised screen: keep the old blind 8 s cadence as the floor.
                System.currentTimeMillis() - lastAction >= 8000 -> answer()
            }
            delay(300)
        }
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

    // ── Summary on /nsdrop ───────────────────────────────────────────────────

    private val configDir get() = File(System.getenv("CLAUDE_CONFIG_DIR") ?: "${System.getProperty("user.home")}/.claude")

    /**
     * The side session's transcript: Claude Code's sessions/<pid>.json records the
     * tmux pane and session id, the transcript is projects/<cwd>/<id>.jsonl. Must be
     * read before [end] — the pid file goes away with the process.
     */
    fun transcriptOf(s: NamedSession): File? = try {
        val meta = File(configDir, "sessions").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> try { Json.parseToJsonElement(f.readText()).jsonObject } catch (_: Exception) { null } }
            .filter { it["tmux"]?.jsonPrimitive?.contentOrNull?.startsWith("${s.tmux}:") == true }
            .maxByOrNull { it["startedAt"]?.jsonPrimitive?.longOrNull ?: 0 }
        val id = meta?.get("sessionId")?.jsonPrimitive?.contentOrNull
        val cwd = meta?.get("cwd")?.jsonPrimitive?.contentOrNull
        if (id == null || cwd == null) { log("transcript: no sessions/*.json with tmux ${s.tmux}"); null }
        else File(configDir, "projects/${cwd.replace(Regex("[^A-Za-z0-9]"), "-")}/$id.jsonl").let { f ->
            if (f.isFile) f else { log("transcript: ${f.path} missing"); null }
        }
    } catch (e: Exception) { log("transcript lookup failed: ${e.describe()}"); null }

    /** User and assistant prose from a transcript, tool traffic dropped, newest kept. */
    private fun dialogue(f: File, maxChars: Int = 60_000): String {
        val parts = f.readLines().mapNotNull { line ->
            val o = try { Json.parseToJsonElement(line).jsonObject } catch (_: Exception) { return@mapNotNull null }
            val role = o["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it == "user" || it == "assistant" } ?: return@mapNotNull null
            val content = (o["message"] as? JsonObject)?.get("content") ?: return@mapNotNull null
            val text = when (content) {
                is JsonPrimitive -> content.contentOrNull
                is JsonArray -> content.mapNotNull { b ->
                    (b as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }?.get("text")?.jsonPrimitive?.contentOrNull
                }.joinToString("\n")
                else -> null
            }?.trim()
            if (text.isNullOrEmpty() || text.startsWith("<system-reminder>")) null else "[$role] $text"
        }
        // PR/release/commit URLs live in tool output, which is dropped above: list them so the summary can link.
        val urls = Regex("""https://github\.com/[\w.-]+/[\w.-]+/(?:pull/\d+|releases/tag/[\w.%-]+|commit/[0-9a-f]{7,40})""")
            .findAll(f.readText().replace("\\/", "/")).map { it.value }.distinct().toList().takeLast(30)
        val links = if (urls.isEmpty()) "" else "\n\n[links seen in tool output]\n" + urls.joinToString("\n")
        return parts.joinToString("\n\n").takeLast(maxChars - links.length) + links
    }

    /** A short summary via headless `claude -p`; Result.failure carries a reason for the user. */
    suspend fun summarize(f: File): Result<String> = withContext(Dispatchers.IO) {
        val text = dialogue(f).ifBlank { return@withContext Result.failure(Exception("empty transcript")) }
        val prompt = "Summarize this Claude Code session for its owner. English, addressed to them as \"you\", formatted as Telegram HTML " +
            "(only <b>, <i>, <code>, <a href>, <blockquote expandable>; • for bullets; escape & < > in plain text; no markdown). Exact order: " +
            "1) <b>Why</b>: the owner's motivation or problem that started the session, one or two sentences; " +
            "2) <b>Outcome</b>: what it led to, one or two sentences (meaning, not a play-by-play; no thread ids or Telegram details); " +
            "3) <b>Done</b>: at most 5 one-line outcome bullets; every PR, release or commit mentioned must be an <a href> link, " +
            "using the URLs from the transcript or the [links seen in tool output] list; " +
            "4) everything else (numbers, caveats, side notes) inside ONE <blockquote expandable>; " +
            "5) last, <b>Needs you</b>: only real blockers — something that stays broken or unfinished until the owner acts. At most 3, one short line each, " +
            "most important first; omit the section if there are none. Optional offers, nice-to-haves, open questions and things to consider " +
            "are not blockers: leave them out or mention them briefly in the expandable quote. " +
            "Never include secret values. Output only the summary: no heading, preamble, English corrections or footers. The transcript follows on stdin."
        try {
            val cmd = (Config.env("TELEGRAM_SUMMARY_CMD") ?: "claude -p --model sonnet").split(Regex("\\s+")) + prompt
            val pb = ProcessBuilder(cmd).directory(File(System.getProperty("user.home")))
            pb.environment().remove("TELEGRAM_SESSION_NAME")
            // The owner's memory carries chat rules (footers, etc.) that don't belong in a summary.
            pb.environment()["CLAUDE_CODE_DISABLE_AUTO_MEMORY"] = "1"
            val p = pb.start()
            p.outputStream.bufferedWriter().use { it.write(text) }
            val out = async { p.inputStream.bufferedReader().readText() }
            val err = async { p.errorStream.bufferedReader().readText() }
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) { p.destroyForcibly(); return@withContext Result.failure(Exception("timed out")) }
            val o = out.await().trim()
            if (p.exitValue() == 0 && o.isNotEmpty()) Result.success(o)
            else Result.failure(Exception("exit ${p.exitValue()}: ${(err.await().trim().ifEmpty { o }).takeLast(300)}"))
        } catch (e: Exception) { Result.failure(Exception(e.describe())) }.onFailure { log("summary failed: ${it.message}") }
    }

    /** Thread of the topic named "Main" in telegram-topics.json, if any. */
    fun mainThread(chatId: String): Long? = try {
        Json.parseToJsonElement(Config.topicsFile.readText()).jsonObject.entries.firstOrNull { (k, v) ->
            k.startsWith("$chatId:") && (v as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull == "Main"
        }?.key?.substringAfter(':')?.toLongOrNull()
    } catch (_: Exception) { null }
}
