package com.selim.livescores.service

import com.selim.livescores.domain.MatchEvent
import java.time.Instant

class EventDeduplicator(private val matchKey: String) {
    fun merge(current: List<MatchEvent>, incoming: List<MatchEvent>, keepLast: Int): List<MatchEvent> {
        if (incoming.isEmpty()) return current

        // Merge current + new, de-dupe with enrichment updates
        val mergedMap = LinkedHashMap<String, MatchEvent>()
        (current + incoming).forEach { e ->
            val (noPlayerKey, candidateKey) = keysFor(e)
            // If we already have an older "no player" placeholder, let the richer event overwrite it.
            val k = if (candidateKey != noPlayerKey && mergedMap.containsKey(noPlayerKey)) noPlayerKey else candidateKey

            val existing = mergedMap[k]
            if (existing == null || isBetter(e, existing)) {
                mergedMap[k] = e
            }
        }

        val ordered = mergedMap.values
            .sortedWith(
                compareBy<MatchEvent>(
                    { TimeParser.parseMinute(it.minute, it.time) ?: Int.MAX_VALUE },
                    { sortTs(it) }
                )
            )

        // Always keep scorers: goals must not be trimmed away when we cap the event list.
        val goals = ordered.filter { normEventType(it.event) == "GOAL" }
        val others = ordered.filterNot { normEventType(it.event) == "GOAL" }

        val kept = if (goals.size >= keepLast) {
            goals.takeLast(keepLast)
        } else {
            val remaining = keepLast - goals.size
            goals + others.takeLast(remaining)
        }

        return kept.sortedWith(
            compareBy<MatchEvent>(
                { TimeParser.parseMinute(it.minute, it.time) ?: Int.MAX_VALUE },
                { sortTs(it) }
            )
        )
    }

    private fun normSide(s: String?): String {
        val v = (s ?: "").trim().lowercase()
        return when {
            v.startsWith("h") -> "h"   // h, home, host...
            v.startsWith("a") -> "a"   // a, away, guest...
            else -> v
        }
    }

    private fun normEventType(s: String?): String = (s ?: "").trim().uppercase()

    private fun dedupMinuteKey(e: MatchEvent): Int {
        val minuteKey = TimeParser.parseMinute(e.minute, e.time) ?: -1
        val type = normEventType(e.event)

        // For goals/cards, providers often fluctuate by +/- 1 minute. We bucket by 2-minute windows.
        // Example: 2' and 3' => same bucket, 67' and 67' => same bucket.
        if (type == "GOAL" || type == "YELLOWCARD" || type == "REDCARD") {
            val baseMinute = if (minuteKey >= 0) (minuteKey / 100) else -1
            return if (baseMinute >= 0) (baseMinute / 2) else -1
        }

        // Default: keep exact ordering key (includes added time as 4502, 9003, ...)
        return minuteKey
    }

    private fun normPlayerKey(player: String?): String {
        val raw = (player ?: "").trim()
        if (raw.isEmpty()) return ""
        val noAccents = java.text.Normalizer
            .normalize(raw, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents
            .lowercase()
            // keep only letters/digits, normalize punctuation/spaces ("V. Gyökeres" == "V Gyokeres")
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun normTimeKey(time: String?): String = TimeParser.normalize(time)

    /**
     * Stable identity for de-dup.
     *
     * The provider can return the *same* logical event multiple times with small differences
     * (homeAway sometimes missing, time formatted as 45 / 45' / 45+2, etc.).
     * Provider event ids are not stable across polls, so we intentionally ignore them.
     *
     * Strategy:
     * - Key = (type + minuteKey) and optionally player (when present).
     *   When a later event becomes "richer" (player/homeAway filled), it replaces the older one.
     */
    private fun keysFor(e: MatchEvent): Pair<String, String> {
        val minuteKey = dedupMinuteKey(e)
        val type = normEventType(e.event)
        val side = normSide(e.homeAway)
        val sidePart = if (side == "h" || side == "a") "|s:$side" else ""
        val noPlayer = "$matchKey|$type|m:$minuteKey$sidePart"

        val p = normPlayerKey(e.player)
        val withPlayer = if (p.isNotEmpty()) "$noPlayer|p:$p" else noPlayer
        return noPlayer to withPlayer
    }

    private fun isBetter(newE: MatchEvent, oldE: MatchEvent): Boolean {
        val newPlayer = (newE.player ?: "").trim()
        val oldPlayer = (oldE.player ?: "").trim()

        val newHasPlayer = newPlayer.isNotEmpty()
        val oldHasPlayer = oldPlayer.isNotEmpty()

        // Prefer enriched events with a player name
        if (newHasPlayer && !oldHasPlayer) return true
        if (!newHasPlayer && oldHasPlayer) return false

        // If both have a player, prefer the more informative one (often full name > initials)
        if (newHasPlayer && oldHasPlayer && newPlayer.length != oldPlayer.length) {
            return newPlayer.length > oldPlayer.length
        }

        // Prefer enriched events with a known side (home/away)
        val newSide = normSide(newE.homeAway)
        val oldSide = normSide(oldE.homeAway)
        val newHasSide = newSide == "h" || newSide == "a"
        val oldHasSide = oldSide == "h" || oldSide == "a"
        if (newHasSide && !oldHasSide) return true
        if (!newHasSide && oldHasSide) return false

        // Prefer enriched events with a usable time string
        val newHasTime = normTimeKey(newE.time).isNotEmpty()
        val oldHasTime = normTimeKey(oldE.time).isNotEmpty()
        if (newHasTime && !oldHasTime) return true
        if (!newHasTime && oldHasTime) return false

        // Otherwise keep the most recent one
        val newTs = newE.ts ?: Instant.EPOCH
        val oldTs = oldE.ts ?: Instant.EPOCH
        return newTs.isAfter(oldTs)
    }

    private fun sortTs(e: MatchEvent): Instant = e.ts ?: Instant.EPOCH
}
