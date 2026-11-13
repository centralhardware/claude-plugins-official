package telegram

import kotlinx.serialization.json.*

/** The tool-facing format names; null parse mode = plain text. */
enum class Fmt { HTML, MARKDOWN, MARKDOWNV2 }

fun fmtOf(format: String?): Fmt? = when (format) {
    "html" -> Fmt.HTML
    "markdown" -> Fmt.MARKDOWN
    "markdownv2" -> Fmt.MARKDOWNV2
    else -> null
}

val FORMATS = listOf("text", "markdown", "markdownv2", "html")
const val FORMAT_DESC =
    "Rendering mode. 'html' (recommended for formatting: <b>, <i>, <code>, <pre>, <a href>) — only &, < and > need escaping. " +
        "'markdown' is legacy Markdown (*bold*, _italic_, `code`). 'markdownv2' is strict and requires escaping every reserved char. " +
        "Default: 'text' (plain, no escaping needed). If the text fails to parse, it is re-sent as plain text rather than dropped."

const val MAX_CHUNK_LIMIT = 4096

/** Telegram reports malformed markup as a 400 — only those are worth retrying unformatted. */
private val PARSE_ERROR_RE = Regex("can't parse|can't find end|unsupported start tag|unclosed|reserved|entities", RegexOption.IGNORE_CASE)

fun isParseError(code: Int?, description: String?): Boolean =
    code == 400 && description != null && PARSE_ERROR_RE.containsMatchIn(description)

/** Split for Telegram's 4096 cap, preferring paragraph/line/space breaks in 'newline' mode. */
fun chunk(text: String, limit: Int, mode: String): List<String> {
    if (text.length <= limit) return listOf(text)
    val out = mutableListOf<String>()
    var rest = text
    while (rest.length > limit) {
        var cut = limit
        if (mode == "newline") {
            val para = rest.lastIndexOf("\n\n", limit)
            val line = rest.lastIndexOf('\n', limit)
            val space = rest.lastIndexOf(' ', limit)
            cut = when {
                para > limit / 2 -> para
                line > limit / 2 -> line
                space > 0 -> space
                else -> limit
            }
        }
        out += rest.substring(0, cut)
        rest = rest.substring(cut).trimStart('\n')
    }
    if (rest.isNotEmpty()) out += rest
    return out
}

// ── Rich messages (Bot API 10.1) ─────────────────────────────────────────────
// Blocks are passed through untouched (the accepted schema was probed live and
// is narrower than the docs); on rejection they're flattened to plain text.

fun parseRich(value: JsonElement?): JsonArray? {
    var raw = value
    if (raw is JsonPrimitive && raw.isString) {
        raw = try { Json.parseToJsonElement(raw.content) } catch (_: Exception) { throw IllegalArgumentException("rich must be a JSON array of blocks") }
    }
    if (raw == null || raw is JsonNull) return null
    val blocks = raw as? JsonArray ?: (raw as? JsonObject)?.get("blocks") as? JsonArray
    if (blocks == null || blocks.isEmpty()) throw IllegalArgumentException("rich must be a non-empty array of blocks")
    return blocks
}

fun richTextToPlain(node: JsonElement?): String = when (node) {
    null, JsonNull -> ""
    is JsonPrimitive -> node.content
    is JsonArray -> node.joinToString("") { richTextToPlain(it) }
    is JsonObject -> {
        val inner = richTextToPlain(node["text"])
        val url = node.str("url")
        if (node.str("type") == "url" && url != null) (if (inner.isNotEmpty()) "$inner ($url)" else url) else inner
    }
}

fun blocksToPlain(blocks: JsonElement?, depth: Int = 0): String {
    val pad = "  ".repeat(depth)
    val lines = mutableListOf<String>()
    for (b in (blocks as? JsonArray).orEmpty()) {
        val o = b as? JsonObject ?: continue
        when (o.str("type")) {
            "list" -> {
                val numbered = o.bool("ordered") == true || o.bool("numbered") == true
                o.arr("items").orEmpty().forEachIndexed { i, item ->
                    val marker = if (numbered) "${i + 1}." else "•"
                    lines += "$pad$marker ${blocksToPlain((item as? JsonObject)?.get("blocks"), depth + 1).trim()}"
                }
            }
            "details", "blockquote" -> {
                val header = richTextToPlain(o["header"])
                if (header.isNotEmpty()) lines += pad + header
                lines += blocksToPlain(o["blocks"], depth + 1)
            }
            "divider" -> lines += "$pad—"
            "table" -> {
                val cells = o.arr("cells").orEmpty().map { row ->
                    (row as? JsonArray).orEmpty().map { c -> richTextToPlain((c as? JsonObject)?.get("text")) }
                }
                cells.forEach { row -> lines += pad + row.joinToString(" | ") }
            }
            else -> lines += pad + richTextToPlain(o["text"])
        }
    }
    return lines.filter { it.isNotBlank() }.joinToString("\n")
}
