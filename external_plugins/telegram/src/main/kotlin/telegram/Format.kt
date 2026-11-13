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

// ── Markdown tables ──────────────────────────────────────────────────────────
// A narrow pipe table becomes an aligned monospace block; a wide one becomes one
// labelled record per row, readable at phone width. Empty cells are dropped.
const val TABLE_PRE_WIDTH = 44

private fun splitRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private fun isTableRow(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.endsWith("|") && t.length > 1
}

private val SEP_CELL = Regex("^:?-+:?$")
private fun isSeparatorRow(line: String): Boolean =
    isTableRow(line) && splitRow(line).let { cells -> cells.isNotEmpty() && cells.all { SEP_CELL.matches(it) } }

fun renderTable(header: List<String>, rows: List<List<String>>, fmt: Fmt?): String {
    val cols = (listOf(header.size) + rows.map { it.size }).max()
    val grid = (listOf(header) + rows).map { r -> List(cols) { i -> r.getOrElse(i) { "" } } }
    val widths = List(cols) { i -> grid.maxOf { it[i].length } }
    val total = widths.sum() + 2 * (cols - 1)
    if (total <= TABLE_PRE_WIDTH) {
        fun row(r: List<String>) = r.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString("  ").trimEnd()
        val body = (listOf(row(grid[0]), widths.joinToString("  ") { "─".repeat(it) }) + grid.drop(1).map(::row)).joinToString("\n")
        return when (fmt) {
            Fmt.HTML -> "<pre>$body</pre>"
            null -> body
            else -> "```\n$body\n```"
        }
    }
    fun bold(s: String) = when (fmt) { Fmt.HTML -> "<b>$s</b>"; null -> s; else -> "*$s*" }
    return grid.drop(1).joinToString("\n\n") { r ->
        val out = mutableListOf(bold(r[0].ifEmpty { "—" }))
        for (i in 1 until cols) {
            if (r[i].isEmpty()) continue
            out += if (grid[0][i].isNotEmpty()) "${grid[0][i]}: ${r[i]}" else r[i]
        }
        out.joinToString("\n")
    }
}

/** Rewrite every pipe table in an outbound message; everything else untouched. */
fun normalizeTables(text: String, format: String?): String {
    if (text.isEmpty() || '|' !in text) return text
    val fmt = fmtOf(format)
    val lines = text.split('\n')
    val out = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        if (isTableRow(lines[i]) && isSeparatorRow(lines.getOrElse(i + 1) { "" })) {
            val header = splitRow(lines[i])
            val rows = mutableListOf<List<String>>()
            var j = i + 2
            while (j < lines.size && isTableRow(lines[j])) rows += splitRow(lines[j++])
            if (rows.isNotEmpty()) {
                out += renderTable(header, rows, fmt)
                i = j
                continue
            }
        }
        out += lines[i]
        i++
    }
    return out.joinToString("\n")
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
                if (cells.isNotEmpty()) renderTable(cells[0], cells.drop(1), null).split('\n').forEach { lines += pad + it }
            }
            else -> lines += pad + richTextToPlain(o["text"])
        }
    }
    return lines.filter { it.isNotBlank() }.joinToString("\n")
}
