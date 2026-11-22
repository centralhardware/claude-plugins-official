package telegram

import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.get.getFileAdditionalInfo
import dev.inmo.tgbotapi.extensions.api.send.media.sendDocument
import dev.inmo.tgbotapi.extensions.api.send.media.sendPhoto
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.api.forwardMessage
import dev.inmo.tgbotapi.extensions.api.send.sendRichMessage
import dev.inmo.tgbotapi.extensions.api.send.setMessageReaction
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.requests.abstracts.FileId
import dev.inmo.tgbotapi.types.reactions.Reaction
import dev.inmo.tgbotapi.extensions.api.send.polls.sendRegularPoll
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.KeyboardMarkup
import dev.inmo.tgbotapi.types.polls.InputPollOption
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
suspend fun sendText(chatId: String, text: String, threadId: Long?, replyTo: Long?, fmt: Fmt?, markup: KeyboardMarkup? = null): Long {
    suspend fun go(f: Fmt?) = Tg.bot.sendMessage(
        chatIdOf(chatId), text, parseMode = f.toParseMode(),
        threadId = threadOf(threadId), replyParameters = replyParams(chatId, replyTo), replyMarkup = markup,
    ).messageId.long
    return try { go(fmt) } catch (e: Exception) {
        if (fmt == null || !e.isParseErr()) throw e
        log("$fmt rejected (${e.describe()}) — resending as plain text")
        go(null)
    }
}

suspend fun sendRich(chatId: String, blocks: JsonArray, threadId: Long?, replyTo: Long?): Long =
    Tg.bot.sendRichMessage(
        chatIdOf(chatId), richMessageOf(blocks),
        threadId = threadOf(threadId), replyParameters = replyParams(chatId, replyTo),
    ).messageId.long

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

class Tools(private val typing: Typing, private val scope: kotlinx.coroutines.CoroutineScope) {
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
                    "buttons" to json(
                        "type" to "array", "items" to json(),
                        "description" to "Inline buttons under the message, for a question with fixed answers: [\"Yes\",\"No\"] (one per row) or [[\"Yes\",\"No\"],[\"Later\"]] (rows). " +
                            "A tap arrives later as a new <channel> event with event=\"button\" and button=<label>; the buttons are then removed so it can't be answered twice. " +
                            "End the turn after asking — don't wait for the tap. Needs text (not rich).",
                    ),
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
        add(json(
            "name" to "send_poll",
            "description" to "Send a Telegram poll. It is non-anonymous, so each vote arrives later as a <channel> event with event=\"poll_answer\" " +
                "(chosen = the picked options; empty when a vote is retracted). Useful in group chats to collect everyone's choice.",
            "inputSchema" to json(
                "type" to "object",
                "properties" to json(
                    "chat_id" to json("type" to "string"),
                    "question" to json("type" to "string"),
                    "options" to json("type" to "array", "items" to json("type" to "string"), "description" to "2–12 answer options."),
                    "multiple" to json("type" to "boolean", "description" to "Allow picking several options."),
                    "thread_id" to json("type" to "string", "description" to "Forum topic; omitted → the topic of the last inbound message."),
                ),
                "required" to listOf("chat_id", "question", "options"),
            ),
        ))
        add(json(
            "name" to "schedule_message",
            "description" to "Queue a text message to be sent to a Telegram chat later. Pass exactly one of at (ISO-8601 with an offset, e.g. 2026-11-21T18:00:00+07:00) or in_minutes. " +
                "The queue is persisted, so it survives a restart; delivery is checked every ~10 s. Returns the id for cancel_scheduled.",
            "inputSchema" to json(
                "type" to "object",
                "properties" to json(
                    "chat_id" to json("type" to "string"),
                    "text" to json("type" to "string"),
                    "at" to json("type" to "string", "description" to "When to send: ISO-8601 with an offset (or epoch seconds)."),
                    "in_minutes" to json("type" to "number", "description" to "Alternative to at: minutes from now."),
                    "thread_id" to json("type" to "string", "description" to "Forum topic; omitted → the topic of the last inbound message."),
                    "format" to json("type" to "string", "enum" to FORMATS, "description" to FORMAT_DESC),
                ),
                "required" to listOf("chat_id", "text"),
            ),
        ))
        add(json(
            "name" to "list_scheduled",
            "description" to "List pending scheduled messages (id, send time, chat, topic, text preview). Optionally filter by chat_id.",
            "inputSchema" to json("type" to "object", "properties" to json("chat_id" to json("type" to "string"))),
        ))
        add(json(
            "name" to "cancel_scheduled",
            "description" to "Cancel a pending scheduled message by the id schedule_message returned.",
            "inputSchema" to json(
                "type" to "object",
                "properties" to json("id" to json("type" to "string")),
                "required" to listOf("id"),
            ),
        ))
        // The side session's own exit: it proposes, the user confirms with a button.
        if (Sessions.isChild) add(json(
            "name" to "request_close",
            "description" to "Ask the user to close this side session once its subject is resolved or completed. Posts the summary in this " +
                "session's topic with two buttons: close (stops the session, deletes the topic, posts a summary in Main) or keep working. Nothing closes without the user's tap. If they keep it open you get an event=\"close_declined\" message; " +
                "otherwise this session is stopped — don't start new work after calling it.",
            "inputSchema" to json(
                "type" to "object",
                "properties" to json(
                    "summary" to json("type" to "string", "description" to "One or two sentences: what was resolved or done. Plain text."),
                ),
                "required" to listOf("summary"),
            ),
        ))
        // Side sessions are spawned by the main session only; a child can't nest them.
        if (!Sessions.isChild) add(json(
            "name" to "spawn_session",
            "description" to "Start a separate Claude session in a new forum topic — the same thing the user's /ns <name> command does — " +
                "and hand it the message that started the subject. Use when a message in Main opens a new subject that will need a continuous " +
                "back-and-forth: from then on every message in that topic goes to the new session, never to this one.",
            "inputSchema" to json(
                "type" to "object",
                "properties" to json(
                    "chat_id" to json("type" to "string"),
                    "name" to json("type" to "string", "description" to "Session/topic name: 1–32 chars, a-z 0-9 - _ (e.g. visa-run)."),
                    "message" to json("type" to "string", "description" to "The user's opening message, verbatim, delivered to the new session as its first turn."),
                    "message_id" to json("type" to "string", "description" to "message_id of the inbound Main message. Pass it: the original is forwarded natively into the topic instead of being re-posted as text."),
                    "image_path" to json("type" to "string", "description" to "Optional: image_path from the inbound message, passed through."),
                    "attachment_file_id" to json("type" to "string", "description" to "Optional: attachment_file_id from the inbound message, passed through."),
                ),
                "required" to listOf("chat_id", "name", "message"),
            ),
        ))
        if (!Sessions.isChild) add(json(
            "name" to "restart_session",
            "description" to "Relaunch a side session in its existing topic when it didn't come up (an event=\"session_dead\" message names it). " +
                "Kills its tmux, starts a fresh claude there, keeps the topic and any undelivered messages.",
            "inputSchema" to json(
                "type" to "object",
                "properties" to json("name" to json("type" to "string", "description" to "Side session name.")),
                "required" to listOf("name"),
            ),
        ))
        if (!Sessions.isChild) add(json(
            "name" to "list_sessions",
            "description" to "List the running side sessions (name, topic thread, alive). Check it before spawn_session: a Main message that belongs to one of them should go there with forward_to_session, not start a new one.",
            "inputSchema" to json("type" to "object", "properties" to json()),
        ))
        if (!Sessions.isChild) add(json(
            "name" to "forward_to_session",
            "description" to "Move a message the user posted in Main by mistake into an existing side session: it is delivered there as a normal turn, and a copy is posted in that topic so the user sees where it went.",
            "inputSchema" to json(
                "type" to "object",
                "properties" to json(
                    "chat_id" to json("type" to "string"),
                    "name" to json("type" to "string", "description" to "Side session name (from list_sessions)."),
                    "message" to json("type" to "string", "description" to "The user's message, verbatim."),
                    "message_id" to json("type" to "string", "description" to "message_id of the inbound Main message. Pass it: the original is forwarded natively into the topic instead of being re-posted as text."),
                    "image_path" to json("type" to "string"),
                    "attachment_file_id" to json("type" to "string"),
                ),
                "required" to listOf("chat_id", "name", "message"),
            ),
        ))
    }

    private fun listSessions(): JsonObject = text(Sessions.load().joinToString("\n") {
        "${it.name} thread=${it.threadId} ${if (Sessions.alive(it)) "alive" else "dead"}"
    }.ifEmpty { "no side sessions" })

    private fun handOff(s: NamedSession, chatId: String, message: String, args: JsonObject) {
        val meta = buildJsonObject {
            put("chat_id", chatId); put("thread_id", s.threadId.toString()); put("topic", "🤖 ${s.name}")
            put("ts", java.time.Instant.now().toString()); put("moved_from", "Main")
            args.anyStr("image_path")?.let { put("image_path", it) }
            args.anyStr("attachment_file_id")?.let { put("attachment_file_id", it) }
        }
        Sessions.enqueue(s, "notifications/claude/channel", buildJsonObject { put("content", message); put("meta", meta) })
    }

    /** Native Telegram forward of the Main message into the session's topic; false if no message_id or it failed. */
    private suspend fun forwardOriginal(chatId: String, s: NamedSession, args: JsonObject): Boolean {
        val id = args.anyStr("message_id")?.toLongOrNull() ?: return false
        return runCatching {
            Tg.bot.forwardMessage(chatIdOf(chatId), chatIdOf(chatId), msgIdOf(id), threadId = threadOf(s.threadId))
        }.isSuccess
    }

    private suspend fun forwardToSession(args: JsonObject): JsonObject {
        val chatId = args.anyStr("chat_id") ?: throw IllegalArgumentException("chat_id required")
        val name = (args.anyStr("name") ?: throw IllegalArgumentException("name required")).lowercase()
        val message = args.anyStr("message") ?: throw IllegalArgumentException("message required")
        val s = Sessions.byName(name)?.takeIf { Sessions.alive(it) }
            ?: return text("no live side session \"$name\"; running: ${Sessions.load().filter { Sessions.alive(it) }.joinToString { it.name }.ifEmpty { "none" }}", error = true)
        if (!forwardOriginal(chatId, s, args))
            Tg.bot.sendMessage(chatIdOf(chatId), "↪️ Moved from Main:\n$message".take(MAX_CHUNK_LIMIT), threadId = threadOf(s.threadId))
        handOff(s, chatId, message, args)
        return text("forwarded to session ${s.name}")
    }

    private suspend fun restartSession(args: JsonObject): JsonObject {
        val name = args.anyStr("name")?.lowercase() ?: throw IllegalArgumentException("name required")
        val s = Sessions.byName(name) ?: return text("no side session \"$name\"", error = true)
        val fresh = Sessions.restart(scope, s).getOrElse { return text("restart_session failed: ${it.message}", error = true) }
        try { Tg.bot.sendMessage(chatIdOf(s.chatId), "🔄 Session \"${s.name}\" restarted.", threadId = threadOf(s.threadId)) } catch (_: Exception) {}
        return text("restarted ${fresh.name}; expect session_alive when it checks in, session_dead if it doesn't")
    }

    private suspend fun requestClose(args: JsonObject): JsonObject {
        val s = Sessions.self() ?: return text("request_close works only in a side session", error = true)
        val summary = args.anyStr("summary")?.trim()?.ifEmpty { null } ?: throw IllegalArgumentException("summary required")
        val markup = InlineKeyboardMarkup(listOf(
            listOf(
                CallbackDataInlineKeyboardButton("🗑 Close", "${CLOSE_PREFIX}drop:${s.name}"),
                CallbackDataInlineKeyboardButton("↩️ Keep working", "${CLOSE_PREFIX}keep:${s.name}"),
            ),
        ))
        Tg.bot.sendMessage(chatIdOf(s.chatId), "✅ Looks done: ${summary.take(3500)}\n\nClose session \"${s.name}\"?",
            threadId = threadOf(s.threadId), replyMarkup = markup)
        return text("asked the user to confirm closing; wait for their choice")
    }

    private suspend fun spawnSession(args: JsonObject): JsonObject {
        val chatId = args.anyStr("chat_id") ?: throw IllegalArgumentException("chat_id required")
        val name = (args.anyStr("name") ?: throw IllegalArgumentException("name required")).lowercase()
        val message = args.anyStr("message") ?: throw IllegalArgumentException("message required")
        val s = Sessions.spawn(scope, name, chatId).getOrElse { return text("spawn_session failed: ${it.message}", error = true) }
        Tg.bot.sendMessage(chatIdOf(chatId), "🤖 Session \"${s.name}\" started for this subject. Messages in this topic go to it; /nsend here stops it.", threadId = threadOf(s.threadId))
        forwardOriginal(chatId, s, args)
        // Spooled now; the child drains it once its own server is up (startup grace covers the prompts).
        handOff(s, chatId, message, args)
        return text("started session ${s.name} in topic thread ${s.threadId}; the opening message is queued for it")
    }

    private fun scheduleMessage(args: JsonObject): JsonObject {
        val chatId = args.anyStr("chat_id") ?: throw IllegalArgumentException("chat_id required")
        if (chatId.startsWith("guest:")) throw IllegalStateException("messages can't be scheduled into a guest chat")
        AccessStore.assertAllowedChat(chatId)
        val body = args.anyStr("text")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("text required")
        val inMinutes = args["in_minutes"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() ?: throw IllegalArgumentException("in_minutes must be a number") }
        val sendAt = Scheduled.parseWhen(args.anyStr("at"), inMinutes)
        val format = args.str("format")
        val threadId = args.anyStr("thread_id")?.toLongOrNull() ?: lastThread[chatId]
        val msg = ScheduledMsg(Scheduled.newId(), chatId, threadId, body, format, sendAt)
        Scheduled.add(msg)
        return text("scheduled (id: ${msg.id}) for ${java.time.Instant.ofEpochMilli(sendAt)}")
    }

    private fun listScheduled(args: JsonObject): JsonObject = text(Scheduled.list(args.anyStr("chat_id")).joinToString("\n") {
        val preview = it.text.replace('\n', ' ').let { t -> if (t.length > 60) t.take(60) + "…" else t }
        "${it.id} at=${java.time.Instant.ofEpochMilli(it.sendAt)} chat=${it.chatId}${it.threadId?.let { t -> " thread=$t" } ?: ""} \"$preview\""
    }.ifEmpty { "no scheduled messages" })

    private fun cancelScheduled(args: JsonObject): JsonObject {
        val id = args.anyStr("id") ?: throw IllegalArgumentException("id required")
        return Scheduled.cancel(id)?.let { text("cancelled $id") } ?: text("no scheduled message with id $id", error = true)
    }

    private fun text(s: String, error: Boolean = false) =
        json("content" to listOf(json("type" to "text", "text" to s)), "isError" to if (error) true else null)

    suspend fun call(name: String, args: JsonObject): JsonObject = try {
        when (name) {
            "reply" -> reply(args)
            "react" -> react(args)
            "download_attachment" -> text(downloadToInbox(args.anyStr("file_id") ?: throw IllegalArgumentException("file_id required")))
            "edit_message" -> edit(args)
            "send_poll" -> sendPoll(args)
            "spawn_session" -> spawnSession(args)
            "list_sessions" -> listSessions()
            "forward_to_session" -> forwardToSession(args)
            "request_close" -> requestClose(args)
            "restart_session" -> restartSession(args)
            "schedule_message" -> scheduleMessage(args)
            "list_scheduled" -> listScheduled(args)
            "cancel_scheduled" -> cancelScheduled(args)
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
        val text = rawText ?: ""
        val replyTo = args.anyStr("reply_to")?.toLongOrNull()
        val files = args.arr("files")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val fmt = fmtOf(format)
        val buttons = parseButtons(args["buttons"])
        if (buttons != null && rich != null) throw IllegalArgumentException("buttons need a text reply, not rich")
        val markup = buttons?.let { rows ->
            var i = 0
            InlineKeyboardMarkup(rows.map { row -> row.map { CallbackDataInlineKeyboardButton(it.take(64), "$ASK_PREFIX${i++}") } })
        }

        // Guest turn: one inline message per query; a second reply edits it.
        Guests.get(chatId)?.let { guest ->
            val flat = text.take(MAX_CHUNK_LIMIT)
            guest.answered?.let { inlineId ->
                val edited = flat.ifEmpty { blocksToPlain(rich) }.take(MAX_CHUNK_LIMIT)
                if (edited.isEmpty()) throw IllegalArgumentException("nothing to edit: pass text or rich")
                Guests.edit(inlineId, edited, if (rich != null && flat.isEmpty()) null else fmt)
                return text("guest query already answered — edited that message instead (Telegram allows one message per guest query)")
            }
            guest.answered = Guests.answer(guest, flat, fmt, rich)
            val skipped = if (files.isNotEmpty()) " (${files.size} file(s) omitted — attachments can't be sent in a guest answer)" else ""
            return text("sent guest answer$skipped")
        }

        // Explicit thread wins; otherwise the topic the last inbound came from.
        val threadId = args.anyStr("thread_id")?.toLongOrNull() ?: lastThread[chatId]

        AccessStore.assertAllowedChat(chatId)
        // Typing is deliberately left running: a reply is often a progress note
        // mid-turn. The Stop hook's turn-done sentinel ends it when the turn does.

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
                    sent += sendText(chatId, part, threadId, rt, fmt, markup.takeIf { i == chunks.lastIndex })
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

        sent.forEach { rememberSent(chatId, it, threadId) }
        return text(if (sent.size == 1) "sent (id: ${sent[0]})" else "sent ${sent.size} parts (ids: ${sent.joinToString(", ")})")
    }

    private suspend fun sendPoll(args: JsonObject): JsonObject {
        val chatId = args.anyStr("chat_id") ?: throw IllegalArgumentException("chat_id required")
        if (chatId.startsWith("guest:")) throw IllegalStateException("polls can't be sent in a guest chat")
        AccessStore.assertAllowedChat(chatId)
        val question = args.anyStr("question") ?: throw IllegalArgumentException("question required")
        val options = args.arr("options")?.map { it.jsonPrimitive.content }.orEmpty()
        if (options.size !in 2..12) throw IllegalArgumentException("a poll needs 2–12 options")
        val threadId = args.anyStr("thread_id")?.toLongOrNull() ?: lastThread[chatId]
        val msg = Tg.bot.sendRegularPoll(
            chatIdOf(chatId), question, options.map { InputPollOption(it) },
            isAnonymous = false, allowsMultipleAnswers = args.bool("multiple") == true, threadId = threadOf(threadId),
        )
        val id = msg.messageId.long
        Polls.put(msg.content.poll.id.string, PollRef(chatId, threadId, id, question, options))
        rememberSent(chatId, id, threadId)
        return text("poll sent (id: $id)")
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
        val body = args.anyStr("text") ?: ""
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
