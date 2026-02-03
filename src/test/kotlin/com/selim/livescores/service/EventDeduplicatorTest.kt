package com.selim.livescores.service

import com.selim.livescores.domain.MatchEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class EventDeduplicatorTest {
    @Test
    fun `merge upgrades player name for same event`() {
        val matchKey = "ls-1"
        val dedup = EventDeduplicator(matchKey)

        val current = listOf(
            event(time = "10", event = "GOAL", player = null, homeAway = "h", ts = Instant.parse("2024-01-01T00:00:01Z"))
        )
        val incoming = listOf(
            event(time = "10", event = "GOAL", player = "Messi", homeAway = "h", ts = Instant.parse("2024-01-01T00:00:02Z"))
        )

        val merged = dedup.merge(current, incoming, keepLast = 30)
        assertEquals(1, merged.size)
        assertEquals("Messi", merged.first().player)
    }

    @Test
    fun `merge keeps goals when trimming`() {
        val matchKey = "ls-2"
        val dedup = EventDeduplicator(matchKey)

        val goals = listOf(
            event(time = "5", event = "GOAL", player = "A", homeAway = "h", ts = Instant.parse("2024-01-01T00:00:05Z")),
            event(time = "70", event = "GOAL", player = "B", homeAway = "a", ts = Instant.parse("2024-01-01T00:01:10Z"))
        )
        val others = listOf(
            event(time = "10", event = "YELLOWCARD", player = "C", homeAway = "h", ts = Instant.parse("2024-01-01T00:00:10Z")),
            event(time = "20", event = "SUBSTITUTION", player = "D", homeAway = "a", ts = Instant.parse("2024-01-01T00:00:20Z")),
            event(time = "30", event = "SUBSTITUTION", player = "E", homeAway = "h", ts = Instant.parse("2024-01-01T00:00:30Z")),
            event(time = "40", event = "YELLOWCARD", player = "F", homeAway = "a", ts = Instant.parse("2024-01-01T00:00:40Z"))
        )

        val merged = dedup.merge(current = emptyList(), incoming = goals + others, keepLast = 3)
        val mergedTypes = merged.map { it.event }.toSet()

        assertTrue(mergedTypes.contains("GOAL"))
        assertEquals(3, merged.size)
    }

    @Test
    fun `goals within 2-minute bucket are deduplicated`() {
        val matchKey = "ls-3"
        val dedup = EventDeduplicator(matchKey)

        val incoming = listOf(
            event(time = "2", event = "GOAL", player = "A", homeAway = "h", ts = Instant.parse("2024-01-01T00:00:02Z")),
            event(time = "3", event = "GOAL", player = "Alex", homeAway = "h", ts = Instant.parse("2024-01-01T00:00:03Z"))
        )

        val merged = dedup.merge(current = emptyList(), incoming = incoming, keepLast = 30)
        assertEquals(1, merged.size)
        assertEquals("Alex", merged.first().player)
    }

    private fun event(
        time: String,
        event: String,
        player: String?,
        homeAway: String,
        ts: Instant
    ): MatchEvent =
        MatchEvent(
            id = null,
            event = event,
            time = time,
            player = player,
            homeAway = homeAway,
            matchId = null,
            ts = ts
        )
}
