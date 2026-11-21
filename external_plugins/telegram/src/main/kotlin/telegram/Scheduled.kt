package telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

data class ScheduledMsg(
    val id: String,
    val chatId: String,
    val threadId: Long?,
    val text: String,
    val format: String?,
    val sendAt: Long,
)

const val MAX_SCHEDULED = 200
const val SCHEDULE_TICK_MS = 10_000L

/**
 * Messages queued by schedule_message, kept in scheduled.json so they survive a
 * restart. Side sessions write the queue too, so every read-modify-write holds an
 * OS file lock; only the polling main server sends.
 */
object Scheduled {
    private val file: File get() = File(Config.stateDir, "scheduled.json")
    private val lockFile: File get() = File(Config.stateDir, "scheduled.lock")
    private val jvmLock = Any()

    private fun <T> locked(block: (MutableList<ScheduledMsg>) -> Pair<T, Boolean>): T = synchronized(jvmLock) {
        Config.stateDir.mkdirs()
        RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.lock().use {
                val all = load()
                val (result, dirty) = block(all)
                if (dirty) {
                    val tmp = File(file.path + ".tmp")
                    tmp.writeText(encode(all).toString())
                    if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
                }
                result
            }
        }
    }

    private fun load(): MutableList<ScheduledMsg> = try {
        decode(Json.parseToJsonElement(file.readText()).jsonArray)
    } catch (_: Exception) { mutableListOf() }

    fun encode(all: List<ScheduledMsg>) = JsonArray(all.map {
        json("id" to it.id, "chat_id" to it.chatId, "thread_id" to it.threadId, "text" to it.text, "format" to it.format, "send_at" to it.sendAt)
    })

    fun decode(arr: JsonArray): MutableList<ScheduledMsg> = arr.mapTo(mutableListOf()) { v ->
        val o = v.jsonObject
        ScheduledMsg(o.str("id")!!, o.str("chat_id")!!, o.long("thread_id"), o.str("text")!!, o.str("format"), o.long("send_at")!!)
    }

    fun add(msg: ScheduledMsg) = locked { all ->
        if (all.size >= MAX_SCHEDULED) throw IllegalStateException("too many scheduled messages (max $MAX_SCHEDULED) — cancel some first")
        all += msg
        Unit to true
    }

    fun list(chatId: String? = null): List<ScheduledMsg> = locked { all ->
        all.filter { chatId == null || it.chatId == chatId }.sortedBy { it.sendAt } to false
    }

    fun cancel(id: String): ScheduledMsg? = locked { all ->
        val hit = all.firstOrNull { it.id == id }
        if (hit != null) all.remove(hit)
        hit to (hit != null)
    }

    /** Remove and return what is due — claimed before sending, so it goes out at most once. */
    fun takeDue(now: Long = System.currentTimeMillis()): List<ScheduledMsg> = locked { all ->
        val due = all.filter { it.sendAt <= now }
        all.removeAll(due)
        due to due.isNotEmpty()
    }

    fun newId(): String = java.util.UUID.randomUUID().toString().substring(0, 8)

    /** `at` (ISO-8601 with an offset, or epoch seconds) or `in_minutes`; exactly one. */
    fun parseWhen(at: String?, inMinutes: Double?, now: Long = System.currentTimeMillis()): Long {
        if ((at == null) == (inMinutes == null)) throw IllegalArgumentException("pass exactly one of at or in_minutes")
        val ts = if (inMinutes != null) {
            if (inMinutes <= 0) throw IllegalArgumentException("in_minutes must be positive")
            now + (inMinutes * 60_000).toLong()
        } else {
            at!!.trim().toLongOrNull()?.let { it * 1000 } ?: try {
                OffsetDateTime.parse(at.trim()).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                try { Instant.parse(at.trim()).toEpochMilli() } catch (_: DateTimeParseException) {
                    throw IllegalArgumentException("at must be ISO-8601 with an offset, e.g. 2026-11-21T18:00:00+07:00 (got \"$at\")")
                }
            }
        }
        if (ts <= now) throw IllegalArgumentException("that time is already in the past")
        if (ts - now > 366L * 24 * 3600 * 1000) throw IllegalArgumentException("can't schedule more than a year ahead")
        return ts
    }

    /** The sender: only the server that owns the poller lock runs it. */
    fun start(scope: CoroutineScope, active: () -> Boolean) = scope.launch {
        while (isActive) {
            delay(SCHEDULE_TICK_MS)
            if (!active()) continue
            val due = try { takeDue() } catch (e: Exception) { log("scheduled.json read failed: ${e.describe()}"); continue }
            for (m in due) try {
                AccessStore.assertAllowedChat(m.chatId)
                val limit = (AccessStore.load().textChunkLimit ?: MAX_CHUNK_LIMIT).coerceIn(1, MAX_CHUNK_LIMIT)
                for (part in chunk(m.text, limit, "length")) {
                    rememberSent(m.chatId, sendText(m.chatId, part, m.threadId, null, fmtOf(m.format)), m.threadId)
                }
                log("scheduled message ${m.id} sent to ${m.chatId}")
            } catch (e: Exception) {
                log("scheduled message ${m.id} failed: ${e.describe()}")
            }
        }
    }
}
