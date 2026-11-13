package telegram

import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.api.send.setMessageReaction
import dev.inmo.tgbotapi.types.abstracts.WithOptionalQuoteInfo
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.chat.Bot
import dev.inmo.tgbotapi.types.chat.GroupChat
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.chat.PublicChat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.message.MessageOrigin
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.FromUserMessage
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.message.abstracts.PossiblyForwardedMessage
import dev.inmo.tgbotapi.types.message.abstracts.PossiblyReplyMessage
import dev.inmo.tgbotapi.types.message.abstracts.PossiblyTopicMessage
import dev.inmo.tgbotapi.types.message.abstracts.RequestGuestContentMessage
import dev.inmo.tgbotapi.types.message.content.*
import dev.inmo.tgbotapi.types.message.textsources.MentionTextSource
import dev.inmo.tgbotapi.types.message.textsources.TextMentionTextSource
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import dev.inmo.tgbotapi.types.queries.callback.MessageCallbackQuery
import dev.inmo.tgbotapi.types.reactions.Reaction
import dev.inmo.tgbotapi.types.update.CallbackQueryUpdate
import dev.inmo.tgbotapi.types.update.GuestMessageUpdate
import dev.inmo.tgbotapi.types.update.MessageUpdate
import dev.inmo.tgbotapi.types.update.abstracts.Update
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Permission-reply spec: "yes abcde" / "n abcde" — 5 letters a-z minus 'l'. */
private val PERMISSION_REPLY_RE = Regex("""^\s*(y|yes|n|no)\s+([a-km-z]{5})\s*$""", RegexOption.IGNORE_CASE)
private val PERM_CALLBACK_RE = Regex("""^perm:(allow|deny|more):([a-km-z]{5})$""")

private val ISO_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** ISO-8601 with milliseconds (`…T05:45:42.000Z`) — hooks parse this. */
fun isoTs(epochMillis: Long): String = ISO_MS.format(Instant.ofEpochMilli(epochMillis))

/**
 * How long to hold an inbound message for companions. Telegram delivers a
 * comment and the messages forwarded with it (and every item of an album) as
 * separate updates a few ms apart; without a short hold each one would start
 * its own turn, the forwarded content arriving cut off from the comment that
 * explains it. Each new part restarts the window.
 */
const val BATCH_WINDOW_MS = 1000L

data class Attachment(val kind: String, val fileId: String, val size: Long?, val mime: String?, val name: String?)

/** One inbound Telegram message, reduced to what the notification needs. */
private class Part(
    val msg: ContentMessage<*>,
    val text: String,
    val photoFileId: String?, // largest size
    val attachment: Attachment?,
    val forwardedFrom: String?,
)

private val Message.chatIdStr: String get() = chat.id.chatId.long.toString()
private val Message.idLong: Long get() = messageId.long
private val Message.epochMillis: Long get() = date.unixMillisLong
private val User.idStr: String get() = id.chatId.long.toString()
private val User.handle: String get() = username?.withoutAt ?: idStr
private val Message.sender: User? get() = (this as? FromUserMessage)?.from

/** Forum topic id — only for messages that belong to a topic. */
private val Message.topicThreadId: Long? get() = (this as? PossiblyTopicMessage)?.threadId?.long

class Inbound(
    private val scope: CoroutineScope,
    private val mcp: () -> McpServer,
    private val typing: Typing,
) {
    private val pendingPermissions = ConcurrentHashMap<String, Triple<String, String, String>>()

    // ── Gate ─────────────────────────────────────────────────────────────────

    private sealed interface Gate {
        data class Deliver(val access: Access) : Gate
        data object Drop : Gate
        data class Pair(val code: String, val isResend: Boolean) : Gate
    }

    private fun gate(msg: ContentMessage<*>): Gate {
        val access = AccessStore.load()
        if (AccessStore.pruneExpired(access)) AccessStore.save(access)
        if (access.dmPolicy == "disabled") return Gate.Drop
        val senderId = msg.sender?.idStr ?: return Gate.Drop
        return when (msg.chat) {
            is PrivateChat -> {
                if (senderId in access.allowFrom) return Gate.Deliver(access)
                if (access.dmPolicy == "allowlist") return Gate.Drop
                for ((code, p) in access.pending) {
                    if (p.senderId == senderId) {
                        // Reply twice max (initial + one reminder), then go silent.
                        if (p.replies >= 2) return Gate.Drop
                        p.replies += 1
                        AccessStore.save(access)
                        return Gate.Pair(code, true)
                    }
                }
                if (access.pending.size >= 3) return Gate.Drop
                val code = AccessStore.newCode()
                val now = System.currentTimeMillis()
                access.pending[code] = PendingEntry(senderId, msg.chatIdStr, now, now + 60 * 60 * 1000)
                AccessStore.save(access)
                Gate.Pair(code, false)
            }
            is GroupChat -> {
                val policy = access.groups[msg.chatIdStr] ?: return Gate.Drop
                if (policy.allowFrom.isNotEmpty() && senderId !in policy.allowFrom) return Gate.Drop
                if (policy.requireMention && !isMentioned(msg, access.mentionPatterns)) return Gate.Drop
                Gate.Deliver(access)
            }
            else -> Gate.Drop
        }
    }

    /** Like gate() for bot commands: no pairing side effects. */
    private fun dmCommandGate(msg: ContentMessage<*>): Pair<Access, String>? {
        if (msg.chat !is PrivateChat) return null
        val senderId = msg.sender?.idStr ?: return null
        val access = AccessStore.load()
        if (AccessStore.pruneExpired(access)) AccessStore.save(access)
        if (access.dmPolicy == "disabled") return null
        if (access.dmPolicy == "allowlist" && senderId !in access.allowFrom) return null
        return access to senderId
    }

    private fun isMentioned(msg: ContentMessage<*>, extra: List<String>?): Boolean {
        val content = msg.content as? TextedContent
        val me = Tg.username
        for (src in content?.textSources.orEmpty()) {
            if (src is MentionTextSource && src.source.equals("@$me", ignoreCase = true)) return true
            if (src is TextMentionTextSource && src.user is Bot && src.user.username?.withoutAt == me) return true
        }
        // A reply to one of our messages counts as an implicit mention.
        if ((msg as? PossiblyReplyMessage)?.replyTo?.sender?.username?.withoutAt == me) return true
        val text = content?.text ?: ""
        for (pat in extra.orEmpty()) {
            try { if (Regex(pat, RegexOption.IGNORE_CASE).containsMatchIn(text)) return true } catch (_: Exception) {}
        }
        return false
    }

    // ── Update dispatch ──────────────────────────────────────────────────────

    suspend fun onUpdate(update: Update) {
        when (update) {
            is GuestMessageUpdate -> onGuest(update.data)
            is MessageUpdate -> (update.data as? ContentMessage<*>)?.let { onMessage(it) }
            is CallbackQueryUpdate -> (update.data as? DataCallbackQuery)?.let { onCallback(it) }
            else -> {}
        }
    }

    private suspend fun onMessage(msg: ContentMessage<*>) {
        val text = (msg.content as? TextContent)?.text
        if (text != null && text.startsWith("/") && handleCommand(msg, text)) return
        val part = toPart(msg) ?: return
        handleInbound(msg, part)
    }

    private fun toPart(msg: ContentMessage<*>): Part? {
        val fwd = forwardLabel(msg)
        val caption = (msg.content as? TextedContent)?.text
        return when (val c = msg.content) {
            is TextContent -> Part(msg, c.text, null, null, fwd)
            is PhotoContent -> Part(msg, caption ?: "(photo)", c.media.fileId.fileId, null, fwd)
            is DocumentContent -> {
                val name = safeName(c.media.fileName)
                Part(msg, caption ?: "(document: ${name ?: "file"})", null,
                    Attachment("document", c.media.fileId.fileId, c.media.fileSize?.bytes?.toLong(), c.media.mimeType?.raw, name), fwd)
            }
            is VoiceContent -> Part(msg, caption ?: "(voice message)", null,
                Attachment("voice", c.media.fileId.fileId, c.media.fileSize?.bytes?.toLong(), c.media.mimeType?.raw, null), fwd)
            is AudioContent -> {
                val name = safeName(c.media.fileName)
                Part(msg, caption ?: "(audio: ${safeName(c.media.title) ?: name ?: "audio"})", null,
                    Attachment("audio", c.media.fileId.fileId, c.media.fileSize?.bytes?.toLong(), c.media.mimeType?.raw, name), fwd)
            }
            is VideoContent -> Part(msg, caption ?: "(video)", null,
                Attachment("video", c.media.fileId.fileId, c.media.fileSize?.bytes?.toLong(), c.media.mimeType?.raw, safeName(c.media.fileName)), fwd)
            is VideoNoteContent -> Part(msg, "(video note)", null,
                Attachment("video_note", c.media.fileId.fileId, c.media.fileSize?.bytes?.toLong(), null, null), fwd)
            is StickerContent -> Part(msg, "(sticker${c.media.emoji?.let { " $it" } ?: ""})", null,
                Attachment("sticker", c.media.fileId.fileId, c.media.fileSize?.bytes?.toLong(), null, null), fwd)
            else -> null // other kinds aren't relayed
        }
    }

    /** "forwarded from …" label from forward_origin; null for an original message. */
    private fun forwardLabel(msg: Message): String? {
        val origin = (msg as? PossiblyForwardedMessage)?.forwardOrigin ?: return null
        val label = when (origin) {
            is MessageOrigin.User -> origin.user.username?.let { "@${it.withoutAt}" }
                ?: listOf(origin.user.firstName, origin.user.lastName).filter { it.isNotBlank() }.joinToString(" ")
            is MessageOrigin.HiddenUser -> origin.name
            is MessageOrigin.Public -> (origin.chat as? PublicChat)?.title
            else -> null
        }
        return safeName(label?.ifBlank { null } ?: "unknown")
    }

    // ── Commands (DM-only; silent elsewhere) ─────────────────────────────────

    private suspend fun handleCommand(msg: ContentMessage<*>, text: String): Boolean {
        val token = text.substringBefore(' ').substringBefore('\n').removePrefix("/")
        val cmd = token.substringBefore('@').lowercase()
        val target = token.substringAfter('@', "")
        if (target.isNotEmpty() && !target.equals(Tg.username, ignoreCase = true)) return false
        if (cmd !in setOf("start", "help", "status", "cancel")) return false
        val gated = dmCommandGate(msg) ?: return true
        val chatId = msg.chatIdStr
        suspend fun say(s: String) = try { Tg.bot.sendMessage(chatIdOf(chatId), s) } catch (e: Exception) { log("command reply failed: ${e.describe()}") }
        when (cmd) {
            "start" -> say(
                "This bot bridges Telegram to a Claude Code session.\n\n" +
                    "To pair:\n" +
                    "1. DM me anything — you'll get a 6-char code\n" +
                    "2. In Claude Code: /telegram:access pair <code>\n\n" +
                    "After that, DMs here reach that session.",
            )
            "help" -> say(
                "Messages you send here route to a paired Claude Code session. " +
                    "Text and photos are forwarded; replies and reactions come back.\n\n" +
                    "/start — pairing instructions\n" +
                    "/status — check your pairing state",
            )
            "status" -> {
                val (access, senderId) = gated
                if (senderId in access.allowFrom) {
                    val name = msg.sender?.username?.let { "@${it.withoutAt}" } ?: senderId
                    say("Paired as $name.")
                } else {
                    val code = access.pending.entries.firstOrNull { it.value.senderId == senderId }?.key
                    say(if (code != null) "Pending pairing — run in Claude Code:\n\n/telegram:access pair $code" else "Not paired. Send me a message to get a pairing code.")
                }
            }
            "cancel" -> {
                // Interrupt the running turn: Escape into the tmux pane, the same key
                // a person at the keyboard would press. A relayed message would only
                // be seen after the turn it was meant to stop.
                val ok = try {
                    ProcessBuilder("tmux", "send-keys", "-t", Config.tmuxSession, "Escape")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start().waitFor() == 0
                } catch (e: Exception) {
                    log("/cancel tmux send-keys failed: $e")
                    false
                }
                typing.stop(chatId)
                say(if (ok) "🛑 Interrupted the running turn." else "Couldn't reach tmux session \"${Config.tmuxSession}\".")
            }
        }
        return true
    }

    // ── Permission relay ─────────────────────────────────────────────────────

    private fun permKeyboard(requestId: String, withMore: Boolean) = InlineKeyboardMarkup(
        listOf(
            listOfNotNull(
                if (withMore) CallbackDataInlineKeyboardButton("See more", "perm:more:$requestId") else null,
                CallbackDataInlineKeyboardButton("✅ Allow", "perm:allow:$requestId"),
                CallbackDataInlineKeyboardButton("❌ Deny", "perm:deny:$requestId"),
            ),
        ),
    )

    /** permission_request from Claude Code → every allowlisted DM (never groups). */
    fun onPermissionRequest(params: JsonObject) {
        val requestId = params.str("request_id") ?: return
        val toolName = params.str("tool_name") ?: ""
        pendingPermissions[requestId] = Triple(toolName, params.str("description") ?: "", params.str("input_preview") ?: "")
        for (chatId in AccessStore.load().allowFrom) {
            scope.launch {
                try {
                    Tg.bot.sendMessage(chatIdOf(chatId), "🔐 Permission: $toolName", replyMarkup = permKeyboard(requestId, true))
                } catch (e: Exception) { log("permission_request send to $chatId failed: ${e.describe()}") }
            }
        }
    }

    private suspend fun onCallback(cq: DataCallbackQuery) {
        suspend fun answer(text: String? = null) = try { Tg.bot.answerCallbackQuery(cq.id, text) } catch (_: Exception) {}
        val m = PERM_CALLBACK_RE.matchEntire(cq.data) ?: return answer()
        if (cq.from.idStr !in AccessStore.load().allowFrom) return answer("Not authorized.")
        val (behavior, requestId) = m.destructured
        val message = (cq as? MessageCallbackQuery)?.message

        if (behavior == "more") {
            val (toolName, description, preview) = pendingPermissions[requestId] ?: return answer("Details no longer available.")
            val pretty = try {
                Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(preview))
            } catch (_: Exception) { preview }
            val expanded = "🔐 Permission: $toolName\n\ntool_name: $toolName\ndescription: $description\ninput_preview:\n$pretty"
            if (message != null) {
                try { Tg.bot.editMessageText(message.chat.id, message.messageId, expanded, replyMarkup = permKeyboard(requestId, false)) } catch (_: Exception) {}
            }
            return answer()
        }

        try {
            mcp().notify("notifications/claude/channel/permission", json("request_id" to requestId, "behavior" to behavior))
        } catch (e: Exception) { log("permission verdict not delivered: $e") }
        pendingPermissions.remove(requestId)
        val label = if (behavior == "allow") "✅ Allowed" else "❌ Denied"
        answer(label)
        // Replace the buttons with the outcome so it can't be answered twice.
        val original = (message?.content as? TextContent)?.text
        if (message != null && original != null) {
            try { Tg.bot.editMessageText(message.chat.id, message.messageId, "$original\n\n$label") } catch (_: Exception) {}
        }
    }

    // ── Inbound messages, batched ────────────────────────────────────────────

    private class Batch(val chatId: String, val threadId: Long?) {
        val parts = mutableListOf<Part>()
        var flush: Job? = null
    }

    private val batches = HashMap<String, Batch>()
    private val batchLock = Any()

    private suspend fun handleInbound(msg: ContentMessage<*>, part: Part) {
        val access = when (val g = gate(msg)) {
            Gate.Drop -> return
            is Gate.Pair -> {
                val lead = if (g.isResend) "Still pending" else "Pairing required"
                try { Tg.bot.sendMessage(chatIdOf(msg.chatIdStr), "$lead — run in Claude Code:\n\n/telegram:access pair ${g.code}") } catch (_: Exception) {}
                return
            }
            is Gate.Deliver -> g.access
        }
        val chatId = msg.chatIdStr
        val msgId = msg.idLong

        // "yes abcde" for a pending permission → structured verdict, not chat.
        PERMISSION_REPLY_RE.matchEntire(part.text)?.let { m ->
            val allow = m.groupValues[1].lowercase().startsWith("y")
            try {
                mcp().notify("notifications/claude/channel/permission", json("request_id" to m.groupValues[2].lowercase(), "behavior" to if (allow) "allow" else "deny"))
            } catch (e: Exception) { log("permission verdict not delivered: $e") }
            scope.launch {
                try { Tg.bot.setMessageReaction(chatIdOf(chatId), msgIdOf(msgId), Reaction.Emoji(if (allow) "✅" else "❌")) } catch (_: Exception) {}
            }
            return
        }

        val threadId = msg.topicThreadId
        if (threadId != null) lastThread[chatId] = threadId else lastThread.remove(chatId)
        val topic = topicConfig(chatId, threadId)
        if (topic?.ignore == true) return // send-only topic

        typing.start(chatId, threadId)
        val ack = access.ackReaction
        if (!ack.isNullOrEmpty()) scope.launch {
            try { Tg.bot.setMessageReaction(chatIdOf(chatId), msgIdOf(msgId), Reaction.Emoji(ack)) } catch (_: Exception) {}
        }

        val key = "$chatId:${threadId ?: ""}:${msg.sender?.idStr}"
        synchronized(batchLock) {
            val batch = batches.getOrPut(key) { Batch(chatId, threadId) }
            batch.parts += part
            batch.flush?.cancel()
            batch.flush = scope.launch {
                delay(BATCH_WINDOW_MS)
                val ready = synchronized(batchLock) {
                    if (batches[key] === batch) batches.remove(key)
                    batch
                }
                deliver(ready, topic)
            }
        }
    }

    private suspend fun downloadPhoto(fileId: String): String? = try {
        downloadToInbox(fileId, "jpg")
    } catch (e: Exception) {
        log("photo download failed: ${e.describe()}")
        null
    }

    private suspend fun deliver(batch: Batch, topic: TopicConfig?) {
        val parts = batch.parts.toList()
        // The sender's own words lead; forwarded material follows in arrival order.
        val primary = parts.firstOrNull { it.forwardedFrom == null } ?: parts.first()
        val ordered = listOf(primary) + parts.filter { it !== primary }
        val content = ordered.joinToString("\n\n") { p ->
            if (p.forwardedFrom != null) "[forwarded from ${p.forwardedFrom}]\n${p.text}" else p.text
        }

        val meta = LinkedHashMap<String, String>()
        val msg = primary.msg
        val from = msg.sender!!
        meta["chat_id"] = batch.chatId
        meta["message_id"] = msg.idLong.toString()
        if (ordered.size > 1) meta["message_ids"] = ordered.joinToString(",") { it.msg.idLong.toString() }
        batch.threadId?.let { meta["thread_id"] = it.toString() }
        topic?.name?.let { meta["topic"] = safeName(it)!! }
        // Operator-written, so the risk isn't injection but a newline breaking the tag.
        topic?.instructions?.let { meta["topic_instructions"] = it.replace(Regex("""[<>\[\]\r\n]+"""), " ").trim() }
        meta["user"] = from.handle
        meta["user_id"] = from.idStr
        meta["ts"] = isoTs(msg.epochMillis)
        if (primary.forwardedFrom != null) meta["forwarded_from"] = primary.forwardedFrom
        addReplyMeta(meta, msg)

        // Attachments: the first keeps the familiar names; later ones get _2, _3…
        var photoN = 0
        var attN = 0
        for (p in ordered) {
            p.photoFileId?.let { id ->
                downloadPhoto(id)?.let { path ->
                    photoN++
                    meta[if (photoN == 1) "image_path" else "image_path_$photoN"] = path
                }
            }
            p.attachment?.let { a ->
                attN++
                putAttachment(meta, a, if (attN == 1) "" else "_$attN")
            }
        }

        try {
            mcp().notify("notifications/claude/channel", json("content" to content, "meta" to meta))
        } catch (e: Exception) {
            // The ack and typing already said "received" — don't leave them waiting.
            log("failed to deliver inbound to Claude: $e")
            typing.stop(batch.chatId)
            try {
                Tg.bot.sendMessage(
                    chatIdOf(batch.chatId),
                    "⚠️ Not delivered — the Claude Code session is not reachable right now. Your message was not seen; send it again once the session is back.",
                    threadId = threadOf(batch.threadId),
                    replyParameters = replyParams(batch.chatId, msg.idLong),
                )
            } catch (_: Exception) {}
        }
    }

    private fun putAttachment(meta: MutableMap<String, String>, a: Attachment, sfx: String = "") {
        meta["attachment_kind$sfx"] = a.kind
        meta["attachment_file_id$sfx"] = a.fileId
        a.size?.let { meta["attachment_size$sfx"] = it.toString() }
        a.mime?.let { meta["attachment_mime$sfx"] = it }
        a.name?.let { meta["attachment_name$sfx"] = it }
    }

    private fun addReplyMeta(meta: MutableMap<String, String>, msg: ContentMessage<*>) {
        val repliedTo = (msg as? PossiblyReplyMessage)?.replyTo ?: return
        meta["reply_to_message_id"] = repliedTo.idLong.toString()
        repliedTo.sender?.let { meta["reply_to_user"] = it.handle }
        // A highlighted fragment arrives as the quote on this message's content — prefer it.
        val quote = (msg.content as? WithOptionalQuoteInfo)?.quote?.text
        val text = quote ?: ((repliedTo as? ContentMessage<*>)?.content as? TextedContent)?.text
        safeName(text)?.take(500)?.takeIf { it.isNotEmpty() }?.let { meta["reply_to_text"] = it }
    }

    // ── Guest queries ────────────────────────────────────────────────────────
    // Gate: the summoning user must be in the DM allowlist; anyone else is dropped
    // in silence (a reply would spend the one answer in a public chat).

    private suspend fun onGuest(msg: RequestGuestContentMessage<*>) {
        val queryId = msg.guestQueryId.string
        val access = AccessStore.load()
        val fromId = msg.from.idStr
        if (access.dmPolicy == "disabled" || fromId !in access.allowFrom) {
            log("guest query from $fromId dropped — not in allowFrom")
            return
        }
        val query = Guests.add(queryId)
        log("guest query from $fromId in chat ${msg.chatIdStr}")

        val part = toPart(msg)
        val imagePath = part?.photoFileId?.let { downloadPhoto(it) }
        val attachment = part?.attachment?.takeIf { it.kind in setOf("document", "voice", "audio", "video") }
        val content = (msg.content as? TextedContent)?.text ?: if (imagePath != null) "(photo)" else "(${attachment?.kind ?: "message"})"

        val meta = LinkedHashMap<String, String>()
        meta["chat_id"] = Guests.key(queryId)
        meta["guest"] = "true"
        meta["guest_chat_id"] = msg.chatIdStr
        (msg.chat as? PublicChat)?.title?.let { meta["guest_chat_title"] = safeName(it)!! }
        meta["message_id"] = msg.idLong.toString()
        meta["user"] = msg.from.handle
        meta["user_id"] = fromId
        meta["ts"] = isoTs(msg.epochMillis)
        addReplyMeta(meta, msg)
        imagePath?.let { meta["image_path"] = it }
        attachment?.let { putAttachment(meta, it) }
        try {
            mcp().notify("notifications/claude/channel", json("content" to content, "meta" to meta))
        } catch (e: Exception) {
            log("failed to deliver guest query to Claude: $e")
            try { Guests.answer(query, "⚠️ Not delivered — the Claude Code session is not reachable right now.", null, null) } catch (_: Exception) {}
        }
    }

    // ── Pairing approvals ────────────────────────────────────────────────────
    // /telegram:access drops approved/<senderId>; confirm and clean up.

    suspend fun checkApprovals() {
        val files = Config.approvedDir.listFiles() ?: return
        for (f in files) {
            try { Tg.bot.sendMessage(chatIdOf(f.name), "Paired! Say hi to Claude.") } catch (e: Exception) {
                log("failed to send approval confirm: ${e.describe()}")
            }
            // Removed either way — don't loop on a broken send.
            f.delete()
        }
    }
}
