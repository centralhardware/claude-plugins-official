package telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FormatTest {
    @Test fun shortTextIsOneChunk() = assertEquals(listOf("hi"), chunk("hi", 10, "length"))

    @Test fun lengthModeHardCuts() = assertEquals(listOf("abcd", "efgh", "ij"), chunk("abcdefghij", 4, "length"))

    @Test fun newlineModePrefersParagraphs() {
        val text = "a".repeat(30) + "\n\n" + "b".repeat(30)
        assertEquals(listOf("a".repeat(30), "b".repeat(30)), chunk(text, 40, "newline"))
    }

    @Test fun richFlattensToPlain() {
        val blocks = Json.parseToJsonElement(
            """[{"type":"heading","size":1,"text":"Title"},
               {"type":"list","items":[{"blocks":[{"type":"paragraph","text":"one"}]},{"blocks":[{"type":"paragraph","text":[{"type":"url","text":"two","url":"https://x"}]}]}]},
               {"type":"divider"}]""",
        ) as JsonArray
        assertEquals("Title\n• one\n• two (https://x)\n—", blocksToPlain(blocks))
    }

    @Test fun richRejectsEmpty() {
        assertFailsWith<IllegalArgumentException> { parseRich(JsonArray(emptyList())) }
    }

    @Test fun parseErrorsDetected() {
        assertTrue(isParseError(400, "Bad Request: can't parse entities: unclosed tag"))
        assertTrue(!isParseError(403, "Forbidden: bot was blocked"))
    }

    @Test fun safeNameStripsDelimiters() = assertEquals("a_b__c_", safeName("a<b>[c]"))
}
