package telegram

import kotlinx.serialization.json.*
import java.io.File

/**
 * Bookkeeping for inbound events that point back at something the bot sent:
 * a reaction or a poll answer names only a message / poll id, so the server
 * remembers which topic each of its messages went to and what each poll asked.
 */

private const val MAX_SENT = 5000
private const val MAX_POLLS = 500

/** "chat:message" → forum thread of messages the bot sent, so a reaction routes to the right session. */
private val sentThreads = object : LinkedHashMap<String, Long>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MAX_SENT
}

fun rememberSent(chatId: String, messageId: Long, threadId: Long?) {
    if (threadId == null) return
    synchronized(sentThreads) { sentThreads["$chatId:$messageId"] = threadId }
}

fun threadOfSent(chatId: String, messageId: Long): Long? = synchronized(sentThreads) { sentThreads["$chatId:$messageId"] }

data class PollRef(val chatId: String, val threadId: Long?, val messageId: Long, val question: String, val options: List<String>)

/** Polls the bot sent, keyed by poll id. Persisted: a vote can come in days later, after a restart. */
object Polls {
    private val file: File get() = File(Config.stateDir, "polls.json")
    private val lock = Any()

    private fun load(): MutableMap<String, PollRef> = try {
        Json.parseToJsonElement(file.readText()).jsonObject.mapValuesTo(LinkedHashMap()) { (_, v) ->
            val o = v.jsonObject
            PollRef(
                o.str("chat_id")!!, o.long("thread_id"), o.long("message_id")!!, o.str("question")!!,
                o.arr("options")!!.map { it.jsonPrimitive.content },
            )
        }
    } catch (_: Exception) { LinkedHashMap() }

    fun put(pollId: String, ref: PollRef) = synchronized(lock) {
        val all = load()
        all[pollId] = ref
        while (all.size > MAX_POLLS) all.remove(all.keys.first())
        val out = JsonObject(all.mapValues { (_, r) ->
            json("chat_id" to r.chatId, "thread_id" to r.threadId, "message_id" to r.messageId, "question" to r.question, "options" to r.options)
        })
        try { file.writeText(out.toString()) } catch (e: Exception) { log("polls.json write failed: ${e.describe()}") }
    }

    fun get(pollId: String): PollRef? = synchronized(lock) { load()[pollId] }
}

/** reply's `buttons`: a flat list is one button per row; a list of lists is rows. */
fun parseButtons(v: JsonElement?): List<List<String>>? {
    val arr = v as? JsonArray ?: return null
    val rows = arr.map { row ->
        when (row) {
            is JsonArray -> row.map { it.jsonPrimitive.content }
            else -> listOf(row.jsonPrimitive.content)
        }.filter { it.isNotBlank() }
    }.filter { it.isNotEmpty() }
    return rows.ifEmpty { null }
}

const val ASK_PREFIX = "ask:"
