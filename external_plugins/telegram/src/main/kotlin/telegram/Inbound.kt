package telegram

import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.api.send.setMessageReaction
import dev.inmo.tgbotapi.types.CallbackQueryId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.reactions.Reaction
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Permission-reply spec: "yes abcde" / "n abcde" — 5 letters a-z minus 'l'. */
private val PERMISSION_REPLY_RE = Regex("""^\s*(y|yes|n|no)\s+([a-km-z]{5})\s*$""", RegexOption.IGNORE_CASE)
private val PERM_CALLBACK_RE = Regex("""^perm:(allow|deny|more):([a-km-z]{5})$""")

/**
 * How long to hold an inbound message for companions. Telegram delivers a
 * comment and the messages forwarded with it (and every item of an album) as
 * separate updates a few ms apart; without a short hold each one would start
 * its own turn, the forwarded content arriving cut off from the comment that
 * explains it. Each new part restarts the window.
 */
private val ISO_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Same shape as JS toISOString() — hooks parse this. */
fun isoTs(epochSeconds: Long?): String = ISO_MS.format(Instant.ofEpochSecond(epochSeconds ?: 0))

const val BATCH_WINDOW_MS = 1000L

data class Attachment(val kind: String, val fileId: String, val size: Long?, val mime: String?, val name: String?)

/** One inbound Telegram message, reduced to what the notification needs. */
private class Part(
    val msg: JsonObject,
    val text: String,
    val photoFileIds: List<String>?, // sizes ascending; largest is last
    val attachment: Attachment?,
    val forwardedFrom: String?,
)

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

    private fun gate(msg: JsonObject): Gate {
        val access = AccessStore.load()
        if (AccessStore.pruneExpired(access)) AccessStore.save(access)
        if (access.dmPolicy == "disabled") return Gate.Drop
        val from = msg.o("from") ?: return Gate.Drop
        val senderId = from.long("id").toString()
        val chat = msg.o("chat") ?: return Gate.Drop
        when (chat.str("type")) {
            "private" -> {
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
                access.pending[code] = PendingEntry(senderId, chat.long("id").toString(), now, now + 60 * 60 * 1000)
                AccessStore.save(access)
                return Gate.Pair(code, false)
            }
            "group", "supergroup" -> {
                val policy = access.groups[chat.long("id").toString()] ?: return Gate.Drop
                if (policy.allowFrom.isNotEmpty() && senderId !in policy.allowFrom) return Gate.Drop
                if (policy.requireMention && !isMentioned(msg, access.mentionPatterns)) return Gate.Drop
                return Gate.Deliver(access)
            }
            else -> return Gate.Drop
        }
    }

    /** Like gate() for bot commands: no pairing side effects. */
    private fun dmCommandGate(msg: JsonObject): Pair<Access, String>? {
        if (msg.o("chat")?.str("type") != "private") return null
        val senderId = msg.o("from")?.long("id")?.toString() ?: return null
        val access = AccessStore.load()
        if (AccessStore.pruneExpired(access)) AccessStore.save(access)
        if (access.dmPolicy == "disabled") return null
        if (access.dmPolicy == "allowlist" && senderId !in access.allowFrom) return null
        return access to senderId
    }

    private fun isMentioned(msg: JsonObject, extra: List<String>?): Boolean {
        val entities = msg.arr("entities") ?: msg.arr("caption_entities") ?: JsonArray(emptyList())
        val text = msg.str("text") ?: msg.str("caption") ?: ""
        val me = Tg.username
        for (e in entities) {
            val o = e as? JsonObject ?: continue
            when (o.str("type")) {
                "mention" -> {
                    val off = o.long("offset")?.toInt() ?: continue
                    val len = o.long("length")?.toInt() ?: continue
                    if (off + len <= text.length && text.substring(off, off + len).equals("@$me", ignoreCase = true)) return true
                }
                "text_mention" -> {
                    val u = o.o("user")
                    if (u?.bool("is_bot") == true && u.str("username") == me) return true
                }
            }
        }
        // A reply to one of our messages counts as an implicit mention.
        if (msg.o("reply_to_message")?.o("from")?.str("username") == me) return true
        for (pat in extra.orEmpty()) {
            try { if (Regex(pat, RegexOption.IGNORE_CASE).containsMatchIn(text)) return true } catch (_: Exception) {}
        }
        return false
    }

    // ── Update dispatch ──────────────────────────────────────────────────────

    suspend fun onUpdate(update: JsonObject) {
        update.o("message")?.let { return onMessage(it) }
        update.o("callback_query")?.let { return onCallback(it) }
        update.o("guest_message")?.let { return onGuest(it) }
    }

    private suspend fun onMessage(msg: JsonObject) {
        val text = msg.str("text")
        if (text != null && text.startsWith("/") && handleCommand(msg, text)) return

        val part = toPart(msg) ?: return
        handleInbound(msg, part)
    }

    private fun toPart(msg: JsonObject): Part? {
        val caption = msg.str("caption")
        val fwd = forwardLabel(msg)
        msg.str("text")?.let { return Part(msg, it, null, null, fwd) }
        msg.arr("photo")?.let { sizes ->
            val ids = sizes.mapNotNull { (it as? JsonObject)?.str("file_id") }
            return Part(msg, caption ?: "(photo)", ids, null, fwd)
        }
        fun att(kind: String, o: JsonObject, name: String? = safeName(o.str("file_name"))) =
            Attachment(kind, o.str("file_id") ?: "", o.long("file_size"), o.str("mime_type"), name)
        msg.o("document")?.let { d ->
            val a = att("document", d)
            return Part(msg, caption ?: "(document: ${a.name ?: "file"})", null, a, fwd)
        }
        msg.o("voice")?.let { v -> return Part(msg, caption ?: "(voice message)", null, att("voice", v, null), fwd) }
        msg.o("audio")?.let { au ->
            val a = att("audio", au)
            return Part(msg, caption ?: "(audio: ${safeName(au.str("title")) ?: a.name ?: "audio"})", null, a, fwd)
        }
        msg.o("video")?.let { v -> return Part(msg, caption ?: "(video)", null, att("video", v), fwd) }
        msg.o("video_note")?.let { v -> return Part(msg, "(video note)", null, att("video_note", v, null), fwd) }
        msg.o("sticker")?.let { s ->
            val emoji = s.str("emoji")?.let { " $it" } ?: ""
            return Part(msg, "(sticker$emoji)", null, att("sticker", s, null).copy(mime = null), fwd)
        }
        return null // service messages and unsupported kinds — same as the TS handlers
    }

    /** "forwarded from …" label from forward_origin; null for an original message. */
    private fun forwardLabel(msg: JsonObject): String? {
        val o = msg.o("forward_origin") ?: return null
        val label = when (o.str("type")) {
            "user" -> o.o("sender_user")?.let { u ->
                u.str("username")?.let { "@$it" } ?: listOfNotNull(u.str("first_name"), u.str("last_name")).joinToString(" ")
            }
            "hidden_user" -> o.str("sender_user_name")
            "chat" -> o.o("sender_chat")?.str("title")
            "channel" -> o.o("chat")?.let { c -> c.str("title") ?: c.str("username")?.let { "@$it" } }
            else -> null
        }
        return safeName(label?.ifBlank { null } ?: "unknown")
    }

    // ── Commands (DM-only; silent elsewhere) ─────────────────────────────────

    private suspend fun handleCommand(msg: JsonObject, text: String): Boolean {
        val token = text.substringBefore(' ').substringBefore('\n').removePrefix("/")
        val cmd = token.substringBefore('@').lowercase()
        val target = token.substringAfter('@', "")
        if (target.isNotEmpty() && !target.equals(Tg.username, ignoreCase = true)) return false
        if (cmd !in setOf("start", "help", "status", "cancel")) return false
        val gated = dmCommandGate(msg) ?: return true
        val chatId = msg.o("chat")!!.long("id").toString()
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
                    val name = msg.o("from")?.str("username")?.let { "@$it" } ?: senderId
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

    private suspend fun onCallback(cq: JsonObject) {
        val id = CallbackQueryId(cq.str("id") ?: return)
        suspend fun answer(text: String? = null) = try { Tg.bot.answerCallbackQuery(id, text) } catch (_: Exception) {}
        val m = PERM_CALLBACK_RE.matchEntire(cq.str("data") ?: "") ?: return answer()
        if (cq.o("from")?.long("id")?.toString() !in AccessStore.load().allowFrom) return answer("Not authorized.")
        val (behavior, requestId) = m.destructured
        val message = cq.o("message")
        val chatId = message?.o("chat")?.long("id")?.toString()
        val messageId = message?.long("message_id")

        if (behavior == "more") {
            val (toolName, description, preview) = pendingPermissions[requestId] ?: return answer("Details no longer available.")
            val pretty = try {
                Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(preview))
            } catch (_: Exception) { preview }
            val expanded = "🔐 Permission: $toolName\n\ntool_name: $toolName\ndescription: $description\ninput_preview:\n$pretty"
            if (chatId != null && messageId != null) {
                try { Tg.bot.editMessageText(chatIdOf(chatId), msgIdOf(messageId), expanded, replyMarkup = permKeyboard(requestId, false)) } catch (_: Exception) {}
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
        val original = message?.str("text")
        if (chatId != null && messageId != null && original != null) {
            try { Tg.bot.editMessageText(chatIdOf(chatId), msgIdOf(messageId), "$original\n\n$label") } catch (_: Exception) {}
        }
    }

    // ── Inbound messages, batched ────────────────────────────────────────────

    private class Batch(val chatId: String, val threadId: Long?, val access: Access) {
        val parts = mutableListOf<Part>()
        var flush: Job? = null
    }

    private val batches = HashMap<String, Batch>()
    private val batchLock = Any()

    private suspend fun handleInbound(msg: JsonObject, part: Part) {
        val access = when (val g = gate(msg)) {
            Gate.Drop -> return
            is Gate.Pair -> {
                val lead = if (g.isResend) "Still pending" else "Pairing required"
                try { Tg.bot.sendMessage(chatIdOf(msg.o("chat")!!.long("id").toString()), "$lead — run in Claude Code:\n\n/telegram:access pair ${g.code}") } catch (_: Exception) {}
                return
            }
            is Gate.Deliver -> g.access
        }
        val chatId = msg.o("chat")!!.long("id").toString()
        val msgId = msg.long("message_id")

        // "yes abcde" for a pending permission → structured verdict, not chat.
        PERMISSION_REPLY_RE.matchEntire(part.text)?.let { m ->
            val allow = m.groupValues[1].lowercase().startsWith("y")
            try {
                mcp().notify("notifications/claude/channel/permission", json("request_id" to m.groupValues[2].lowercase(), "behavior" to if (allow) "allow" else "deny"))
            } catch (e: Exception) { log("permission verdict not delivered: $e") }
            if (msgId != null) scope.launch {
                try { Tg.bot.setMessageReaction(chatIdOf(chatId), msgIdOf(msgId), Reaction.Emoji(if (allow) "✅" else "❌")) } catch (_: Exception) {}
            }
            return
        }

        val threadId = if (msg.bool("is_topic_message") == true) msg.long("message_thread_id") else null
        if (threadId != null) lastThread[chatId] = threadId else lastThread.remove(chatId)
        val topic = topicConfig(chatId, threadId)
        if (topic?.ignore == true) return // send-only topic

        typing.start(chatId, threadId)
        val ack = access.ackReaction
        if (!ack.isNullOrEmpty() && msgId != null) scope.launch {
            try { Tg.bot.setMessageReaction(chatIdOf(chatId), msgIdOf(msgId), Reaction.Emoji(ack)) } catch (_: Exception) {}
        }

        val sender = msg.o("from")?.long("id")
        val key = "$chatId:${threadId ?: ""}:$sender"
        synchronized(batchLock) {
            val batch = batches.getOrPut(key) { Batch(chatId, threadId, access) }
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

    private suspend fun downloadPhoto(fileIds: List<String>): String? = try {
        downloadToInbox(fileIds.last(), "jpg")
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
        val from = msg.o("from")!!
        meta["chat_id"] = batch.chatId
        msg.long("message_id")?.let { meta["message_id"] = it.toString() }
        if (ordered.size > 1) meta["message_ids"] = ordered.mapNotNull { it.msg.long("message_id") }.joinToString(",")
        batch.threadId?.let { meta["thread_id"] = it.toString() }
        topic?.name?.let { meta["topic"] = safeName(it)!! }
        // Operator-written, so the risk isn't injection but a newline breaking the tag.
        topic?.instructions?.let { meta["topic_instructions"] = it.replace(Regex("""[<>\[\]\r\n]+"""), " ").trim() }
        meta["user"] = from.str("username") ?: from.long("id").toString()
        meta["user_id"] = from.long("id").toString()
        meta["ts"] = isoTs(msg.long("date"))
        if (primary.forwardedFrom != null) meta["forwarded_from"] = primary.forwardedFrom
        addReplyMeta(meta, msg)

        // Attachments: the first keeps the familiar names; later ones get _2, _3…
        var photoN = 0
        var attN = 0
        for (p in ordered) {
            p.photoFileIds?.takeIf { it.isNotEmpty() }?.let { ids ->
                downloadPhoto(ids)?.let { path ->
                    photoN++
                    meta[if (photoN == 1) "image_path" else "image_path_$photoN"] = path
                }
            }
            p.attachment?.let { a ->
                attN++
                val sfx = if (attN == 1) "" else "_$attN"
                meta["attachment_kind$sfx"] = a.kind
                meta["attachment_file_id$sfx"] = a.fileId
                a.size?.let { meta["attachment_size$sfx"] = it.toString() }
                a.mime?.let { meta["attachment_mime$sfx"] = it }
                a.name?.let { meta["attachment_name$sfx"] = it }
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
                    replyParameters = replyParams(batch.chatId, msg.long("message_id")),
                )
            } catch (_: Exception) {}
        }
    }

    private fun addReplyMeta(meta: MutableMap<String, String>, msg: JsonObject) {
        val repliedTo = msg.o("reply_to_message") ?: return
        meta["reply_to_message_id"] = repliedTo.long("message_id").toString()
        repliedTo.o("from")?.let { meta["reply_to_user"] = it.str("username") ?: it.long("id").toString() }
        // A highlighted fragment arrives in message.quote — prefer it.
        val text = msg.o("quote")?.str("text") ?: repliedTo.str("text") ?: repliedTo.str("caption")
        safeName(text)?.take(500)?.takeIf { it.isNotEmpty() }?.let { meta["reply_to_text"] = it }
    }

    // ── Guest queries ────────────────────────────────────────────────────────
    // Gate: the summoning user must be in the DM allowlist; anyone else is dropped
    // in silence (a reply would spend the one answer in a public chat).

    private suspend fun onGuest(msg: JsonObject) {
        val queryId = msg.str("guest_query_id") ?: return
        val from = msg.o("from")
        val access = AccessStore.load()
        val fromId = from?.long("id")?.toString()
        if (access.dmPolicy == "disabled" || fromId == null || fromId !in access.allowFrom) {
            log("guest query from ${fromId ?: "unknown"} dropped — not in allowFrom")
            return
        }
        val query = Guests.add(queryId)
        val chat = msg.o("chat")
        log("guest query from $fromId in chat ${chat?.long("id")}")

        val part = toPart(msg)
        val imagePath = part?.photoFileIds?.let { downloadPhoto(it) }
        val attachment = part?.attachment?.takeIf { it.kind in setOf("document", "voice", "audio", "video") }
        val content = msg.str("text") ?: msg.str("caption") ?: if (imagePath != null) "(photo)" else "(${attachment?.kind ?: "message"})"

        val meta = LinkedHashMap<String, String>()
        meta["chat_id"] = Guests.key(queryId)
        meta["guest"] = "true"
        meta["guest_chat_id"] = chat?.long("id").toString()
        chat?.str("title")?.let { meta["guest_chat_title"] = safeName(it)!! }
        meta["message_id"] = msg.long("message_id").toString()
        meta["user"] = from.str("username") ?: fromId
        meta["user_id"] = fromId
        meta["ts"] = isoTs(msg.long("date"))
        addReplyMeta(meta, msg)
        imagePath?.let { meta["image_path"] = it }
        attachment?.let { a ->
            meta["attachment_kind"] = a.kind
            meta["attachment_file_id"] = a.fileId
            a.size?.let { meta["attachment_size"] = it.toString() }
            a.mime?.let { meta["attachment_mime"] = it }
            a.name?.let { meta["attachment_name"] = it }
        }
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
