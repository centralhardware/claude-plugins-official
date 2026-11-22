package telegram

import dev.inmo.tgbotapi.extensions.api.chat.forum.createForumTopic
import dev.inmo.tgbotapi.extensions.api.chat.forum.deleteForumTopic
import dev.inmo.tgbotapi.types.MessageThreadId
import dev.inmo.tgbotapi.types.message.HTMLParseMode
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
 * Named side sessions, started by the main session (spawn_session): a separate `claude` in its own tmux session,
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

    /** Side session: the tmux session Main runs in, to find it among the ListAgents peers. */
    val mainTmux: String get() = System.getenv("TELEGRAM_MAIN_TMUX")?.ifBlank { null } ?: "claude"

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
            add("TELEGRAM_MAIN_TMUX=${Config.tmuxSession}")
            Config.token?.let { add("TELEGRAM_BOT_TOKEN=$it") }
            System.getenv("CLAUDE_CONFIG_DIR")?.let { add("CLAUDE_CONFIG_DIR=$it") }
            System.getenv("PATH")?.let { add("PATH=$it") }
        }.joinToString(" ") { sh(it) }
        val home = System.getProperty("user.home")
        // The shell outlives claude, so a crash leaves the pane readable instead of vanishing.
        val cmd = "env $env $launchCmd; exec \$SHELL"
        check(tmux("new-session", "-d", "-s", s.tmux, "-c", home, cmd)) { "tmux new-session failed." }
        scope.launch { answerPrompts(s.tmux) }
    }

    // ── Liveness ─────────────────────────────────────────────────────────────

    /** Written by the child's server when it hands over its first message; the parent's watcher waits for it. */
    private fun aliveFile(name: String) = File(spoolRoot, "$name.alive")

    fun markAlive(name: String) { spoolRoot.mkdirs(); aliveFile(name).writeText(System.currentTimeMillis().toString()) }

    fun checkedIn(s: NamedSession) = aliveFile(s.name).let { it.isFile && (it.readText().trim().toLongOrNull() ?: 0) >= s.createdAt }

    /**
     * Parent side: no check-in within [timeoutMs] → restart once, then tell Main only if it still fails.
     * Runs in the server, so the Main model neither waits nor schedules anything.
     */
    fun watchCheckIn(scope: CoroutineScope, s: NamedSession, timeoutMs: Long = 180_000, retry: Boolean = true) {
        scope.launch {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val cur = byName(s.name) ?: return@launch
                if (cur.createdAt != s.createdAt) return@launch  // restarted meanwhile: that launch has its own watcher
                if (checkedIn(cur)) { log("side session \"${s.name}\" checked in"); return@launch }
                delay(1000)
            }
            val cur = byName(s.name)?.takeIf { it.createdAt == s.createdAt } ?: return@launch
            val pane = paneText(cur.tmux)?.lines()?.filter { it.isNotBlank() }?.takeLast(8)?.joinToString("\n").orEmpty()
            log("side session \"${s.name}\" didn't check in; pane:\n$pane")
            if (retry) {
                restart(scope, cur).onSuccess { fresh ->
                    try { Tg.bot.sendMessage(chatIdOf(s.chatId), "🔄 No sign of life after 3 min — restarted.", threadId = threadOf(s.threadId)) } catch (_: Exception) {}
                    watchCheckIn(scope, fresh, timeoutMs, retry = false)
                }
            } else {
                val esc = pane.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                try {
                    Tg.bot.sendMessage(chatIdOf(s.chatId), "⚠️ Session \"${s.name}\" didn't start even after a restart. Last screen:\n<pre>$esc</pre>".take(4096),
                        parseMode = HTMLParseMode, threadId = threadOf(mainThread(s.chatId)))
                } catch (e: Exception) { log("check-in failure post failed: ${e.describe()}") }
            }
        }
    }

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
        aliveFile(name).delete()
        summaryFile(name).delete()
    }

    /** Kills the tmux session and forgets the binding. */
    fun end(s: NamedSession) {
        tmux("kill-session", "-t", "=${s.tmux}")
        remove(s.name)
    }

    fun interrupt(s: NamedSession) = tmux("send-keys", "-t", s.tmux, "Escape")

    // ── Close: summary in Main, topic deleted ────────────────────────────────

    /** The summary the session wrote itself (request_close), kept until it is posted at close. */
    private fun summaryFile(name: String) = File(spoolRoot, "$name.summary")

    /** Child side: store this session's own closing summary (Telegram HTML). */
    fun saveSummary(name: String, html: String) {
        spoolRoot.mkdirs()
        val tmp = File(spoolRoot, "$name.summary.tmp")
        tmp.writeText(html)
        Files.move(tmp.toPath(), summaryFile(name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun hasSummary(name: String) = summaryFile(name).isFile

    /**
     * Stops [s], posts the summary it wrote itself in Main and deletes its topic.
     * No transcript re-read or headless run: the session is the only context that spends tokens on it.
     * True if the topic was deleted.
     */
    suspend fun drop(scope: CoroutineScope, s: NamedSession): Boolean {
        val body = try { summaryFile(s.name).readText().trim().ifEmpty { null } } catch (_: Exception) { null }
            ?: "⚠️ no summary: the session closed without writing one"
        end(s)
        summaryFile(s.name).delete()
        scope.launch {
            val thread = threadOf(mainThread(s.chatId))
            val esc = { t: String -> t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
            try {
                Tg.bot.sendMessage(chatIdOf(s.chatId), "📝 <b>Session \"${esc(s.name)}\"</b> (closed)\n\n$body".take(4096),
                    parseMode = HTMLParseMode, threadId = thread)
            } catch (e: Exception) {
                // Model-written HTML can be malformed: fall back to the raw text.
                log("summary HTML rejected, sending plain: ${e.describe()}")
                try { Tg.bot.sendMessage(chatIdOf(s.chatId), "📝 Session \"${s.name}\" (closed)\n\n$body".take(4096), threadId = thread) }
                catch (e2: Exception) { log("summary post failed: ${e2.describe()}") }
            }
        }
        return try { Tg.bot.deleteForumTopic(chatIdOf(s.chatId), MessageThreadId(s.threadId)); true }
            catch (e: Exception) { log("deleteForumTopic failed: ${e.describe()}"); false }
    }

    /** What a summary must look like — shown to the session in request_close's schema. */
    const val SUMMARY_SPEC = "Telegram HTML summary of this whole session for its owner, in English, addressed as \"you\" " +
        "(only <b>, <i>, <code>, <a href>, <blockquote expandable>; • for bullets; escape & < > in plain text; no markdown). Order: " +
        "1) <b>Why</b>: what started the session, one or two sentences; 2) <b>Outcome</b>: what it led to, one or two sentences; " +
        "3) <b>Done</b>: at most 5 one-line bullets, every PR, release or commit as an <a href> link; " +
        "4) other details in ONE <blockquote expandable>; 5) <b>Needs you</b>: only real blockers, at most 3 lines, omitted if none. " +
        "No secret values, no heading, no English-correction footer."

    /** Thread of the topic named "Main" in telegram-topics.json, if any. */
    fun mainThread(chatId: String): Long? = try {
        Json.parseToJsonElement(Config.topicsFile.readText()).jsonObject.entries.firstOrNull { (k, v) ->
            k.startsWith("$chatId:") && (v as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull == "Main"
        }?.key?.substringAfter(':')?.toLongOrNull()
    } catch (_: Exception) { null }
}
