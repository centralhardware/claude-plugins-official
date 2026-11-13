package telegram

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/** Paths and env, resolved once at boot. */
object Config {
    private val home = System.getProperty("user.home")
    val stateDir = File(System.getenv("TELEGRAM_STATE_DIR") ?: "$home/.claude/channels/telegram")
    val accessFile = File(stateDir, "access.json")
    val approvedDir = File(stateDir, "approved")
    val envFile = File(stateDir, ".env")
    val inboxDir = File(stateDir, "inbox")
    val pidFile = File(stateDir, "bot.pid")
    private val configDir = File(System.getenv("CLAUDE_CONFIG_DIR") ?: "$home/.claude")
    val topicsFile = File(configDir, "telegram-topics.json")
    val turnDoneFile = File(configDir, "telegram-turn-done")
    val commandsFile = File(configDir, "telegram-commands.json")
    val tmuxSession = System.getenv("CLAUDE_TMUX_SESSION") ?: "claude"

    /** .env values; the real environment wins. Plugin-spawned servers get no env block. */
    private val dotenv: Map<String, String> by lazy {
        try {
            setOwnerOnly(envFile)
            envFile.readLines().mapNotNull { Regex("""^(\w+)=(.*)$""").find(it)?.destructured?.let { (k, v) -> k to v } }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    fun env(key: String): String? = System.getenv(key) ?: dotenv[key]

    val token: String? get() = env("TELEGRAM_BOT_TOKEN")
    val static: Boolean get() = env("TELEGRAM_ACCESS_MODE") == "static"
}

fun setOwnerOnly(f: File) {
    try { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) } catch (_: Exception) {}
}

private val TOKEN_RE = Regex("""bot\d+:[A-Za-z0-9_-]+""")

/** Library messages can carry request URLs — never let the token reach a log. */
fun log(msg: String) {
    System.err.println("telegram channel: ${msg.replace(TOKEN_RE, "bot<token>")}")
}

@Serializable
data class PendingEntry(
    val senderId: String,
    val chatId: String,
    val createdAt: Long,
    val expiresAt: Long,
    var replies: Int = 1,
)

@Serializable
data class GroupPolicy(
    val requireMention: Boolean = true,
    val allowFrom: List<String> = emptyList(),
)

@Serializable
data class Access(
    var dmPolicy: String = "pairing", // pairing | allowlist | disabled
    val allowFrom: MutableList<String> = mutableListOf(),
    val groups: MutableMap<String, GroupPolicy> = mutableMapOf(),
    val pending: MutableMap<String, PendingEntry> = mutableMapOf(),
    val mentionPatterns: List<String>? = null,
    /** Emoji to react with on receipt. Empty string disables. */
    val ackReaction: String? = null,
    /** off | first | all. Default first. */
    val replyToMode: String? = null,
    val textChunkLimit: Int? = null,
    /** length | newline */
    val chunkMode: String? = null,
)

/**
 * access.json: re-read on every use so /telegram:access edits apply live. In
 * static mode it's snapshotted at boot and never written (pairing downgraded).
 */
object AccessStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; explicitNulls = false }
    private val random = SecureRandom()

    private val boot: Access? = if (Config.static) readFile().also {
        if (it.dmPolicy == "pairing") {
            log("static mode — dmPolicy \"pairing\" downgraded to \"allowlist\"")
            it.dmPolicy = "allowlist"
        }
        it.pending.clear()
    } else null

    private fun readFile(): Access {
        val f = Config.accessFile
        if (!f.exists()) return Access()
        return try {
            json.decodeFromString<Access>(f.readText())
        } catch (_: Exception) {
            try { f.renameTo(File("${f.path}.corrupt-${System.currentTimeMillis()}")) } catch (_: Exception) {}
            log("access.json is corrupt, moved aside. Starting fresh.")
            Access()
        }
    }

    fun load(): Access = boot ?: readFile()

    fun save(a: Access) {
        if (Config.static) return
        Config.stateDir.mkdirs()
        val tmp = File(Config.accessFile.path + ".tmp")
        tmp.writeText(json.encodeToString(Access.serializer(), a) + "\n")
        setOwnerOnly(tmp)
        Files.move(tmp.toPath(), Config.accessFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun pruneExpired(a: Access): Boolean {
        val now = System.currentTimeMillis()
        return a.pending.entries.removeIf { it.value.expiresAt < now }
    }

    fun newCode(): String = ByteArray(3).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Outbound gate — tools can only target chats the inbound gate delivers from. */
    fun assertAllowedChat(chatId: String) {
        if (chatId.startsWith("guest:")) {
            if (Guests.get(chatId) != null) return
            throw IllegalStateException("guest query $chatId is unknown or expired — guest answers must be sent within the hour")
        }
        val a = load()
        if (chatId in a.allowFrom || chatId in a.groups) return
        throw IllegalStateException("chat $chatId is not allowlisted — add via /telegram:access")
    }
}

/** Per-topic config from $CLAUDE_CONFIG_DIR/telegram-topics.json, re-read per message. */
data class TopicConfig(val name: String?, val instructions: String?, val ignore: Boolean)

fun topicConfig(chatId: String, threadId: Long?): TopicConfig? = try {
    val parsed = Json.parseToJsonElement(Config.topicsFile.readText()).jsonObject
    val hit = (if (threadId != null) parsed["$chatId:$threadId"] ?: parsed["$chatId:*"] else null) ?: parsed[chatId]
    (hit as? JsonObject)?.let { TopicConfig(it.str("name"), it.str("instructions"), it.bool("ignore") == true) }
} catch (_: Exception) { null }

/** Bot command menu from $CLAUDE_CONFIG_DIR/telegram-commands.json; invalid entries dropped. */
fun extraCommands(): List<JsonObject> = try {
    Json.parseToJsonElement(Config.commandsFile.readText()).jsonArray.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val c = o.str("command") ?: return@mapNotNull null
        val d = o.str("description") ?: return@mapNotNull null
        if (!Regex("^[a-z0-9_]{1,32}$").matches(c) || d.length !in 1..256) null
        else json("command" to c, "description" to d)
    }
} catch (_: Exception) { emptyList() }

fun turnDoneAt(): Long = try { Config.turnDoneFile.readText().trim().toLong() } catch (_: Exception) { 0 }

/** Uploader-controlled names land inside the <channel> tag — strip delimiters. */
fun safeName(s: String?): String? = s?.replace(Regex("""[<>\[\]\r\n;]"""), "_")
