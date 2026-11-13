package telegram

import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.get.getFileAdditionalInfo
import dev.inmo.tgbotapi.extensions.api.send.media.sendDocument
import dev.inmo.tgbotapi.extensions.api.send.media.sendPhoto
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.api.send.setMessageReaction
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.types.reactions.Reaction
import kotlinx.serialization.json.*
import java.io.File

const val MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024
private val PHOTO_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp")

/** Last forum topic seen per chat, so reply() threads without the model passing it back. */
val lastThread = java.util.concurrent.ConcurrentHashMap<String, Long>()

/** The server's own state is the one thing Claude never has a reason to send. */
fun assertSendable(f: String) {
    val real: String
    val stateReal: String
    try {
        real = File(f).canonicalPath
        stateReal = Config.stateDir.canonicalPath
    } catch (_: Exception) { return }
    val inbox = "$stateReal${File.separator}inbox"
    if (real.startsWith(stateReal + File.separator) && !real.startsWith(inbox + File.separator)) {
        throw IllegalStateException("refusing to send channel state: $f")
    }
}

fun replyParams(chatId: String, messageId: Long?): ReplyParameters? =
    messageId?.let { ReplyParameters(chatIdOf(chatId), msgIdOf(it)) }

/** sendMessage with the plain-text fallback: a stray '*' must not lose the message. */
suspend fun sendText(chatId: String, text: String, threadId: Long?, replyTo: Long?, fmt: Fmt?): Long {
    suspend fun go(f: Fmt?) = Tg.bot.sendMessage(
        chatIdOf(chatId), text, parseMode = f.toParseMode(),
        threadId = threadOf(threadId), replyParameters = replyParams(chatId, replyTo),
    ).messageId.long
    return try { go(fmt) } catch (e: Exception) {
        if (fmt == null || !e.isParseErr()) throw e
        log("$fmt rejected (${e.describe()}) — resending as plain text")
        go(null)
    }
}

suspend fun sendRich(chatId: String, blocks: JsonArray, threadId: Long?, replyTo: Long?): Long {
    val res = Tg.raw("sendRichMessage", json(
        "chat_id" to chatId.toLong(),
        "message_thread_id" to threadId,
        "reply_parameters" to replyTo?.let { json("message_id" to it) },
        "rich_message" to json("blocks" to blocks),
    ))
    return res.obj()?.long("message_id") ?: throw IllegalStateException("sendRichMessage returned no message_id")
}

suspend fun downloadToInbox(fileId: String, fallbackExt: String = "bin"): String {
    val info = Tg.bot.getFileAdditionalInfo(FileId(fileId))
    val path = info.filePath
    val bytes = Tg.bot.downloadFile(info)
    // file_path is from Telegram, but strip to safe chars anyway.
    val ext = (if ('.' in path) path.substringAfterLast('.') else fallbackExt).replace(Regex("[^a-zA-Z0-9]"), "").ifEmpty { fallbackExt }
    val unique = info.fileUniqueId.string.replace(Regex("[^a-zA-Z0-9_-]"), "").ifEmpty { "dl" }
    Config.inboxDir.mkdirs()
    val out = File(Config.inboxDir, "${System.currentTimeMillis()}-$unique.$ext")
    out.writeBytes(bytes)
    return out.path
}

class Tools(private val typing: Typing) {
    fun list(): JsonArray = buildJsonArray {
        add(json(
            "name" to "reply",
            "description" to "Reply on Telegram. Pass chat_id from the inbound message. Optionally pass reply_to (message_id) for threading, and files (absolute paths) to attach images or documents. For anything structured or long, use text with format: \"html\" — the rich parameter renders mangled here and is left to headless senders.",
            "inputSchema" to json(
                "type" to "object",
                "properties" to json(
                    "chat_id" to json("type" to "string"),
                    "text" to json("type" to "string", "description" to "Message text. Optional when rich is given, where it serves as the plain-text fallback."),
                    "rich" to json(
                        "type" to "array", "items" to json("type" to "object"),
                        "description" to "Rich message blocks (Bot API 10.1 sendRichMessage) — use for structured or long replies. " +
                            "Accepted blocks: {type:\"paragraph\",text}, {type:\"heading\",size:<number>,text}, " +
                            "{type:\"list\",items:[{blocks:[…]}],ordered?}, {type:\"details\",header,blocks:[…]} (collapsible — fold long passages here), " +
                            "{type:\"blockquote\",blocks:[…]}, {type:\"table\",cells:[[{text}…]…]}, {type:\"pre\",text}, {type:\"divider\"}, {type:\"footer\",text}. " +
                            "A text value is a string, an array, or a tagged object: {type:\"bold\"|\"italic\"|\"code\"|\"spoiler\"|\"bot_command\",text} or {type:\"url\",text,url}. " +
                            "If Telegram rejects the payload it is flattened to plain text rather than lost.",
                    ),
                    "reply_to" to json("type" to "string", "description" to "Message ID to thread under. Use message_id from the inbound <channel> block."),
                    "thread_id" to json("type" to "string", "description" to "Forum topic to post into. Use thread_id from the inbound <channel> block. Omitted → the topic the last inbound message from this chat came from."),
                    "files" to json("type" to "array", "items" to json("type" to "string"), "description" to "Absolute file paths to attach. Images send as photos (inline preview); other types as documents. Max 50MB each."),
                    "format" to json("type" to "string", "enum" to FORMATS, "description" to FORMAT_DESC),
                ),
                "required" to listOf("chat_id"),
            ),
        ))
        add(json(
            "name" to "react",
            "description" to "Add an emoji reaction to a Telegram message. Telegram only accepts a fixed whitelist (👍 👎 ❤ 🔥 👀 🎉 etc) — non-whitelisted emoji will be rejected.",
            "inputSchema" to json(
                "type" to "object",
                "properties" to json("chat_id" to json("type" to "string"), "message_id" to json("type" to "string"), "emoji" to json("type" to "string")),
                "required" to listOf("chat_id", "message_id", "emoji"),
            ),
        ))
        add(json(
            "name" to "download_attachment",
            "description" to "Download a file attachment from a Telegram message to the local inbox. Use when the inbound <channel> meta shows attachment_file_id. Returns the local file path ready to Read. Telegram caps bot downloads at 20MB.",
            "inputSchema" to json(
                "type" to "object",
                "properties" to json("file_id" to json("type" to "string", "description" to "The attachment_file_id from inbound meta")),
                "required" to listOf("file_id"),
            ),
        ))
        add(json(
            "name" to "edit_message",
            "description" to "Edit a message the bot previously sent. Useful for interim progress updates. Edits don't trigger push notifications — send a new reply when a long task completes so the user's device pings.",
            "inputSchema" to json(
                "type" to "object",
                "properties" to json(
                    "chat_id" to json("type" to "string"),
                    "message_id" to json("type" to "string"),
                    "text" to json("type" to "string"),
                    "format" to json("type" to "string", "enum" to FORMATS, "description" to FORMAT_DESC),
                ),
                "required" to listOf("chat_id", "message_id", "text"),
            ),
        ))
    }

    private fun text(s: String, error: Boolean = false) =
        json("content" to listOf(json("type" to "text", "text" to s)), "isError" to if (error) true else null)

    suspend fun call(name: String, args: JsonObject): JsonObject = try {
        when (name) {
            "reply" -> reply(args)
            "react" -> react(args)
            "download_attachment" -> text(downloadToInbox(args.anyStr("file_id") ?: throw IllegalArgumentException("file_id required")))
            "edit_message" -> edit(args)
            else -> text("unknown tool: $name", error = true)
        }
    } catch (e: Exception) {
        text("$name failed: ${e.describe()}", error = true)
    }

    private suspend fun reply(args: JsonObject): JsonObject {
        val chatId = args.anyStr("chat_id") ?: throw IllegalArgumentException("chat_id required")
        val format = args.str("format")
        val rich = parseRich(args["rich"])
        val rawText = args.anyStr("text")
        if (rawText == null && rich == null) throw IllegalArgumentException("reply needs text or rich")
        val text = normalizeTables(rawText ?: "", format)
        val replyTo = args.anyStr("reply_to")?.toLongOrNull()
        val files = args.arr("files")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val fmt = fmtOf(format)

        // Guest turn: one inline message per query; a second reply edits it.
        Guests.get(chatId)?.let { guest ->
            val flat = text.take(MAX_CHUNK_LIMIT)
            guest.answered?.let { inlineId ->
                val edited = flat.ifEmpty { blocksToPlain(rich) }.take(MAX_CHUNK_LIMIT)
                if (edited.isEmpty()) throw IllegalArgumentException("nothing to edit: pass text or rich")
                Guests.edit(inlineId, edited, fmt, rich)
                return text("guest query already answered — edited that message instead (Telegram allows one message per guest query)")
            }
            guest.answered = Guests.answer(guest, flat, fmt, rich)
            val skipped = if (files.isNotEmpty()) " (${files.size} file(s) omitted — attachments can't be sent in a guest answer)" else ""
            return text("sent guest answer$skipped")
        }

        // Explicit thread wins; otherwise the topic the last inbound came from.
        val threadId = args.anyStr("thread_id")?.toLongOrNull() ?: lastThread[chatId]

        AccessStore.assertAllowedChat(chatId)
        typing.stop(chatId)

        for (f in files) {
            assertSendable(f)
            val size = File(f).also { if (!it.isFile) throw IllegalArgumentException("no such file: $f") }.length()
            if (size > MAX_ATTACHMENT_BYTES) throw IllegalArgumentException("file too large: $f (${"%.1f".format(size / 1024.0 / 1024.0)}MB, max 50MB)")
        }

        val access = AccessStore.load()
        val limit = (access.textChunkLimit ?: MAX_CHUNK_LIMIT).coerceIn(1, MAX_CHUNK_LIMIT)
        val mode = access.chunkMode ?: "length"
        val replyMode = access.replyToMode ?: "first"
        val threadReply = replyTo.takeIf { replyMode != "off" }
        val sent = mutableListOf<Long>()

        if (rich != null) {
            // Rich messages carry their own structure and are never chunked.
            try {
                sent += sendRich(chatId, rich, threadId, threadReply)
            } catch (e: Exception) {
                log("rich message rejected (${e.describe()}) — resending as plain text")
                for (part in chunk(text.ifEmpty { blocksToPlain(rich) }, limit, mode)) {
                    sent += sendText(chatId, part, threadId, threadReply, null)
                }
            }
        } else {
            val chunks = chunk(text, limit, mode)
            try {
                chunks.forEachIndexed { i, part ->
                    val rt = threadReply.takeIf { replyMode == "all" || i == 0 }
                    sent += sendText(chatId, part, threadId, rt, fmt)
                }
            } catch (e: Exception) {
                throw IllegalStateException("reply failed after ${sent.size} of ${chunks.size} chunk(s) sent: ${e.describe()}")
            }
        }

        // Files go as separate messages, threaded under reply_to if present.
        for (f in files) {
            val file = File(f)
            val input = InputFile.fromFile(file)
            val msg = if (file.extension.lowercase() in PHOTO_EXTS) {
                Tg.bot.sendPhoto(chatIdOf(chatId), input, threadId = threadOf(threadId), replyParameters = replyParams(chatId, threadReply))
            } else {
                Tg.bot.sendDocument(chatIdOf(chatId), input, threadId = threadOf(threadId), replyParameters = replyParams(chatId, threadReply))
            }
            sent += msg.messageId.long
        }

        return text(if (sent.size == 1) "sent (id: ${sent[0]})" else "sent ${sent.size} parts (ids: ${sent.joinToString(", ")})")
    }

    private suspend fun react(args: JsonObject): JsonObject {
        val chatId = args.anyStr("chat_id") ?: throw IllegalArgumentException("chat_id required")
        if (chatId.startsWith("guest:")) throw IllegalStateException("reactions aren't available in a guest chat — the bot isn't a member of it")
        AccessStore.assertAllowedChat(chatId)
        val messageId = args.anyStr("message_id")?.toLongOrNull() ?: throw IllegalArgumentException("message_id required")
        Tg.bot.setMessageReaction(chatIdOf(chatId), msgIdOf(messageId), Reaction.Emoji(args.anyStr("emoji") ?: ""))
        return text("reacted")
    }

    private suspend fun edit(args: JsonObject): JsonObject {
        val chatId = args.anyStr("chat_id") ?: throw IllegalArgumentException("chat_id required")
        AccessStore.assertAllowedChat(chatId)
        val format = args.str("format")
        val fmt = fmtOf(format)
        val body = normalizeTables(args.anyStr("text") ?: "", format)
        Guests.get(chatId)?.let { q ->
            val inlineId = q.answered ?: throw IllegalStateException("nothing to edit yet — answer the guest query with reply first")
            Guests.edit(inlineId, body, fmt)
            return text("edited guest answer")
        }
        val messageId = args.anyStr("message_id")?.toLongOrNull() ?: throw IllegalArgumentException("message_id required")
        suspend fun go(f: Fmt?) = Tg.bot.editMessageText(chatIdOf(chatId), msgIdOf(messageId), body, parseMode = f.toParseMode())
        val edited = try { go(fmt) } catch (e: Exception) {
            if (fmt == null || !e.isParseErr()) throw e
            log("$fmt rejected on edit (${e.describe()}) — retrying as plain text")
            go(null)
        }
        return text("edited (id: ${edited.messageId.long})")
    }
}
