package telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.BotException
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.MessageThreadId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import dev.inmo.tgbotapi.types.message.MarkdownParseMode
import dev.inmo.tgbotapi.types.message.MarkdownV2ParseMode
import dev.inmo.tgbotapi.types.message.ParseMode
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.LogLevel
import dev.inmo.tgbotapi.utils.DefaultKTgBotAPIKSLog
import dev.inmo.tgbotapi.types.rich.InputRichBlock
import dev.inmo.tgbotapi.types.rich.InputRichMessage
import dev.inmo.tgbotapi.types.rich.InputRichMessageBlocks
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

object Tg {
    lateinit var bot: TelegramBot
    var username = ""

    fun init(token: String) {
        // ktgbotapi logs every failed request with a stack trace, including the
        // parse errors we retry as plain text on purpose. Our own handlers log
        // what matters, so keep only its unexpected errors, one line each.
        DefaultKTgBotAPIKSLog = KSLog { level, _, message, throwable ->
            if (level >= LogLevel.ERROR && throwable !is BotException) log("ktgbotapi: $message${throwable?.let { " ($it)" } ?: ""}")
        }
        bot = telegramBot(token)
    }
}

/**
 * The tool takes rich blocks as JSON; ktgbotapi's own InputRichBlock serializer
 * decodes them into its typed blocks. A payload it can't decode fails like a
 * Telegram rejection would, and the caller falls back to plain text.
 */
private val richJson = Json { ignoreUnknownKeys = true }

fun richMessageOf(blocks: JsonArray): InputRichMessage =
    InputRichMessageBlocks(richJson.decodeFromJsonElement(ListSerializer(InputRichBlock.serializer()), blocks))

fun chatIdOf(s: String): ChatId = ChatId(RawChatId(s.toLong()))
fun msgIdOf(v: Long): MessageId = MessageId(v)
fun threadOf(v: Long?): MessageThreadId? = v?.let { MessageThreadId(it) }

fun Fmt?.toParseMode(): ParseMode? = when (this) {
    Fmt.HTML -> HTMLParseMode
    Fmt.MARKDOWN -> MarkdownParseMode
    Fmt.MARKDOWNV2 -> MarkdownV2ParseMode
    null -> null
}

val Throwable.tgCode: Int? get() = (this as? BotException)?.response?.errorCode
val Throwable.tgDescription: String? get() = (this as? BotException)?.response?.description
fun Throwable.isParseErr(): Boolean = isParseError(tgCode, tgDescription)
fun Throwable.describe(): String = tgDescription ?: message ?: toString()

// ── JSON helpers ─────────────────────────────────────────────────────────────

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.o(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Tool arguments arrive loosely typed: accept "123" and 123 alike. */
fun JsonObject.anyStr(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { this[key] !is JsonNull }

fun json(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) if (v != null) put(k, toJson(v))
}

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.filter { it.value != null }.associate { it.key.toString() to toJson(it.value) })
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
}

operator fun JsonObject.plus(other: JsonObject): JsonObject = JsonObject(this.toMap() + other.toMap())
