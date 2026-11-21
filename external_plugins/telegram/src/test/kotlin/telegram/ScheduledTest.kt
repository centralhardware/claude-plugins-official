package telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScheduledTest {
    private val now = 1_800_000_000_000L

    @Test fun inMinutes() = assertEquals(now + 90_000, Scheduled.parseWhen(null, 1.5, now))

    @Test fun isoWithOffset() =
        assertEquals(java.time.OffsetDateTime.parse("2027-02-01T08:00:00+07:00").toInstant().toEpochMilli(),
            Scheduled.parseWhen("2027-02-01T08:00:00+07:00", null, now))

    @Test fun epochSeconds() = assertEquals((now / 1000 + 60) * 1000, Scheduled.parseWhen("${now / 1000 + 60}", null, now))

    @Test fun rejectsBothOrNeither() {
        assertFailsWith<IllegalArgumentException> { Scheduled.parseWhen(null, null, now) }
        assertFailsWith<IllegalArgumentException> { Scheduled.parseWhen("2027-01-15T08:00:00Z", 5.0, now) }
    }

    @Test fun rejectsPastAndGarbage() {
        assertFailsWith<IllegalArgumentException> { Scheduled.parseWhen("2020-01-01T00:00:00Z", null, now) }
        assertFailsWith<IllegalArgumentException> { Scheduled.parseWhen("tomorrow", null, now) }
        assertFailsWith<IllegalArgumentException> { Scheduled.parseWhen(null, -1.0, now) }
    }

    @Test fun roundTrip() {
        val msgs = listOf(ScheduledMsg("a1", "42", 7L, "hi\nthere", "html", now), ScheduledMsg("b2", "43", null, "x", null, now + 1))
        assertEquals(msgs, Scheduled.decode(Scheduled.encode(msgs)))
    }
}
