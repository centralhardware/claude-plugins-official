package telegram

import dev.inmo.tgbotapi.extensions.api.answers.answerGuestQuery
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendActionTyping
import dev.inmo.tgbotapi.types.GuestQueryId
import dev.inmo.tgbotapi.types.InlineMessageId
import dev.inmo.tgbotapi.types.InlineQueryId
import dev.inmo.tgbotapi.types.InlineQueries.InlineQueryResult.InlineQueryResultArticle
import dev.inmo.tgbotapi.types.InlineQueries.InputMessageContent.InputMessageContent
import dev.inmo.tgbotapi.types.InlineQueries.InputMessageContent.InputRichMessageContent
import dev.inmo.tgbotapi.types.InlineQueries.InputMessageContent.InputTextMessageContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

// ── Guest mode (Bot API 10.0) ────────────────────────────────────────────────
// Summoned by @mention in a chat the bot isn't in: one answer via
// answerGuestQuery, addressed by a synthetic `guest:<query id>` chat_id so it
// can never collide with (or be mistaken for) an allowlisted chat.
object Guests {
    private const val TTL_MS = 60 * 60_000L
    private const val MAX = 50

    class Query(val queryId: String, val expiresAt: Long) {
        /** inline_message_id of the one answer, once sent. */
        @Volatile var answered: String? = null
    }

    private val queries = LinkedHashMap<String, Query>()
    private val random = SecureRandom()

    fun key(queryId: String) = "guest:$queryId"

    @Synchronized fun prune() {
        val now = System.currentTimeMillis()
        queries.entries.removeIf { it.value.expiresAt <= now }
        while (queries.size > MAX) queries.remove(queries.keys.first())
    }

    @Synchronized fun get(chatId: String): Query? {
        if (!chatId.startsWith("guest:")) return null
        prune()
        return queries[chatId]
    }

    @Synchronized fun add(queryId: String): Query {
        prune()
        return Query(queryId, System.currentTimeMillis() + TTL_MS).also { queries[key(queryId)] = it }
    }

    private fun article(content: InputMessageContent) = InlineQueryResultArticle(
        InlineQueryId(ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }),
        "Answer", // never rendered — input_message_content is what lands
        content,
    )

    private suspend fun send(q: Query, content: InputMessageContent): String =
        Tg.bot.answerGuestQuery(GuestQueryId(q.queryId), article(content)).inlineMessageId.string

    suspend fun answer(q: Query, text: String, fmt: Fmt?, rich: JsonArray?): String {
        if (rich != null) {
            return try {
                send(q, InputRichMessageContent(richMessageOf(rich)))
            } catch (e: Exception) {
                log("rich guest answer rejected (${e.describe()}) — resending as plain text")
                send(q, InputTextMessageContent(text.ifEmpty { blocksToPlain(rich) }))
            }
        }
        return try {
            send(q, InputTextMessageContent(text, fmt.toParseMode()))
        } catch (e: Exception) {
            if (fmt == null || !e.isParseErr()) throw e
            log("$fmt rejected on guest answer (${e.describe()}) — retrying as plain text")
            send(q, InputTextMessageContent(text))
        }
    }

    /**
     * ktgbotapi has no inline rich edit, so a revised rich answer is re-sent as
     * its plain-text flattening (the caller passes that as [text]).
     */
    suspend fun edit(inlineId: String, text: String, fmt: Fmt?) {
        suspend fun go(f: Fmt?) = Tg.bot.editMessageText(InlineMessageId(inlineId), text, parseMode = f.toParseMode())
        try { go(fmt) } catch (e: Exception) {
            if (fmt == null || !e.isParseErr()) throw e
            log("$fmt rejected on guest edit (${e.describe()}) — retrying as plain text")
            go(null)
        }
    }
}

// ── Typing keep-alive ────────────────────────────────────────────────────────
// Telegram's chat action expires after ~5s; re-send every 4s until reply()
// clears it, a hook marks the turn done, or a 10-minute cap elapses.
class Typing(private val scope: CoroutineScope) {
    private class Entry(val job: Job, @Volatile var until: Long, @Volatile var startedAt: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun start(chatId: String, threadId: Long?) {
        val now = System.currentTimeMillis()
        entries[chatId]?.let {
            it.until = now + MAX_MS
            it.startedAt = now
            return
        }
        lateinit var entry: Entry
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive) {
                // In a forum, an action without the thread id shows in General.
                try { Tg.bot.sendActionTyping(chatIdOf(chatId), threadOf(threadId)) } catch (_: Exception) {}
                delay(REFRESH_MS)
                if (System.currentTimeMillis() > entry.until || turnDoneAt() >= entry.startedAt) break
            }
            entries.remove(chatId, entry)
        }
        entry = Entry(job, now + MAX_MS, now)
        entries[chatId] = entry
        job.start()
    }

    fun stop(chatId: String) {
        entries.remove(chatId)?.job?.cancel()
    }

    companion object {
        const val REFRESH_MS = 4000L
        const val MAX_MS = 10 * 60_000L
    }
}
