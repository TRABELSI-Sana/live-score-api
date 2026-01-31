package com.selim.livescores.service

import com.selim.livescores.domain.MatchEvent
import com.selim.livescores.domain.MatchState
import com.selim.livescores.repository.redis.MatchStateStore
import com.selim.livescores.repository.redis.LiveMatchesStore
import com.selim.livescores.sse.SseHub
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class MatchService(
    private val matchStateStore: MatchStateStore,
    private val liveMatchesStore: LiveMatchesStore,
    private val sseHub: SseHub,
    private val redis: StringRedisTemplate
) {

    /**
     * Exposed for pollers: compute the stable key for a provider match (fixtures vs live) before we build key lists.
     */
    fun normalizeForKeys(providerMatch: MatchState, boardKeysHint: List<String>? = null): MatchState =
        normalizeProviderMatch(providerMatch, boardKeysHint)

    private val BOARD_KEYS = "livescores:board-keys"

    fun getLiveMatches(): List<MatchState> =
        liveMatchesStore.getAll()
            .mapNotNull { matchStateStore.get(it) }
            .sortedBy { it.scheduled ?: "" }

    fun getLiveMatchKeys(): List<String> = liveMatchesStore.getAll().toList()

    fun getBoardMatches(): List<MatchState> {
        // Safety: the provider can expose the same match under multiple ids (fixtures vs live).
        // De-duplicate for the UI so we don't render the same game twice.
        val raw = getBoardMatchKeys()
            .mapNotNull { matchStateStore.get(it) }

        fun identityKey(m: MatchState): String {
            // Prefer fixtureId when present (stable across the day).
            m.fixtureId?.let { return "fx:$it" }
            val c = m.competition?.id?.toString() ?: "?"
            val h = m.home?.id?.toString() ?: "?"
            val a = m.away?.id?.toString() ?: "?"
            val t = (m.scheduled ?: "").trim()
            return "cmp:$c|h:$h|a:$a|t:$t"
        }

        fun minuteValue(time: String?): Int {
            // Accept: "45", "45+2", "45'", "45+2'", also trims spaces.
            val t0 = (time ?: "").trim()
            if (t0.isEmpty()) return -1
            val t = t0
                .replace("’", "'")
                .replace(" ", "")
                .removeSuffix("'")

            val m = Regex("""^(\d+)(?:\+(\d+))?$""").matchEntire(t) ?: return -1
            val base = m.groupValues[1].toIntOrNull() ?: return -1
            val add = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            return base * 100 + add
        }

        fun better(a: MatchState, b: MatchState): MatchState {
            // Prefer the one that looks “more advanced” (later minute) or has richer data.
            val aMin = minuteValue(a.time)
            val bMin = minuteValue(b.time)
            if (aMin != bMin) return if (aMin > bMin) a else b

            val aEvents = a.lastEvents.size
            val bEvents = b.lastEvents.size
            if (aEvents != bEvents) return if (aEvents > bEvents) a else b

            // Prefer one that has an id (live match id) if the other doesn't.
            val aHasId = a.id != null
            val bHasId = b.id != null
            if (aHasId != bHasId) return if (aHasId) a else b

            return a
        }

        val deduped = LinkedHashMap<String, MatchState>()
        raw.forEach { m ->
            val k = identityKey(m)
            val existing = deduped[k]
            deduped[k] = if (existing == null) m else better(existing, m)
        }

        return deduped.values
            .sortedWith(compareBy<MatchState>({ it.competition?.name ?: "" }, { it.scheduled ?: "" }))
    }

    fun getBoardMatchKeys(): List<String> =
        redis.opsForList().range(BOARD_KEYS, 0, -1) ?: emptyList()

    fun replaceBoardMatchKeys(keys: List<String>) {
        redis.delete(BOARD_KEYS)
        val distinct = keys.filter { it.isNotBlank() }.distinct()
        if (distinct.isNotEmpty()) {
            redis.opsForList().rightPushAll(BOARD_KEYS, distinct)
        }
    }



    /**
     * Freeze last known state as FINISHED so it stays visible on the board,
     * but we stop polling events for it (poller filters by status).
     */
    fun markAsFinished(matchKey: String): MatchState? {
        val current = matchStateStore.get(matchKey) ?: return null
        if (current.status == "FINISHED") return current
        val updated = current.copy(status = "FINISHED", time = "FT")
        matchStateStore.put(updated)
        // publish per-match state update too (optional, harmless)
        sseHub.publish(matchKey, "state", updated)
        return updated
    }

    fun getOrInitState(matchKey: String): MatchState {
        val existing = matchStateStore.get(matchKey)
        if (existing != null) return existing

        val placeholder = MatchState(
            id = matchKey.removePrefix("ls-").toLongOrNull(),
            scheduled = null,
            status = "UNKNOWN",
            time = null,
            competition = null,
            home = null,
            away = null,
            scores = null,
            lastEvents = emptyList()
        )
        matchStateStore.put(placeholder)
        return placeholder
    }

    fun upsertFromProvider(providerMatch: MatchState, boardKeysHint: List<String>? = null): MatchState {
        val normalized = normalizeProviderMatch(providerMatch, boardKeysHint)
        val key = normalized.matchKey
        val current = matchStateStore.get(key)

        val merged = normalized.copy(
            lastEvents = current?.lastEvents ?: providerMatch.lastEvents
        )

        matchStateStore.put(merged)
        sseHub.publish(key, "state", merged)
        return merged
    }

    /**
     * The provider can return live matches without a `fixture_id`.
     * If we already have the fixture version in Redis (from fixtures/today), we attach it so the key stays stable.
     */
    private fun normalizeProviderMatch(providerMatch: MatchState, boardKeysHint: List<String>? = null): MatchState {
        if (providerMatch.fixtureId != null) return providerMatch

        val compId = providerMatch.competition?.id
        val homeId = providerMatch.home?.id
        val awayId = providerMatch.away?.id
        val sched = providerMatch.scheduled?.trim()

        if (compId == null || homeId == null || awayId == null || sched.isNullOrBlank()) return providerMatch

        // Try to find a planned match already stored for today.
        val keys = boardKeysHint ?: getBoardMatchKeys()

        val existing = keys
            .asSequence()
            .mapNotNull { matchStateStore.get(it) }
            .firstOrNull { m ->
                m.fixtureId != null &&
                    m.competition?.id == compId &&
                    m.home?.id == homeId &&
                    m.away?.id == awayId &&
                    (m.scheduled?.trim() == sched)
            }

        return if (existing?.fixtureId != null) providerMatch.copy(fixtureId = existing.fixtureId) else providerMatch
    }

    /**
     * Append newly ingested events.
     *
     * Provider behavior: same logical event can arrive first with empty player, later with player filled.
     * We must (1) de-duplicate, (2) allow enrichment updates, and (3) never drop scorers (GOAL) when trimming.
     */
    fun appendEvents(matchKey: String, newEvents: List<MatchEvent>, keepLast: Int = 30): MatchState {
        if (newEvents.isEmpty()) return getOrInitState(matchKey)

        val current = getOrInitState(matchKey)

        fun parseMinute(minute: Int?, time: String?): Int? {
            if (minute != null) return minute
            val t0 = (time ?: "").trim()
            if (t0.isEmpty()) return null
            // Handles "45+2", "90+3", "11", and also "45'" / "45+2'".
            val t = t0
                .replace("’", "'")
                .replace(" ", "")
                .removeSuffix("'")

            val m = Regex("""^(\d+)(?:\+(\d+))?$""").matchEntire(t) ?: return null
            val base = m.groupValues[1].toIntOrNull() ?: return null
            val add = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            // Keep ordering stable for added-time (e.g., 45+2 => 4502)
            return base * 100 + add
        }

        fun normSide(s: String?): String {
            val v = (s ?: "").trim().lowercase()
            return when {
                v.startsWith("h") -> "h"   // h, home, host...
                v.startsWith("a") -> "a"   // a, away, guest...
                else -> v
            }
        }

        fun normEventType(s: String?): String = (s ?: "").trim().uppercase()

        fun normPlayerKey(player: String?): String = (player ?: "")
            .trim()
            .lowercase()
            // keep only alphanumerics to normalize ("F. Sakala" == "F Sakala")
            .replace(Regex("[^a-z0-9]"), "")

        fun normTimeKey(time: String?): String = (time ?: "")
            .trim()
            .replace("’", "'")
            .replace(" ", "")

        /**
         * Stable identity for de-dup.
         *
         * The provider can return the *same* logical event multiple times with small differences
         * (homeAway sometimes missing, time formatted as 45 / 45' / 45+2, etc.).
         *
         * Strategy:
         * - If provider gives a stable id, use it.
         * - Else: key = (type + minuteKey) and optionally player (when present).
         *   When a later event becomes "richer" (player/homeAway filled), it replaces the older one.
         */
        fun keysFor(e: MatchEvent): Pair<String, String> {
            val id = (e.id ?: "").trim()
            if (id.isNotEmpty()) {
                val k = "$matchKey|id:$id"
                return k to k
            }

            val minuteKey = parseMinute(e.minute, e.time) ?: -1
            val type = normEventType(e.event)
            val noPlayer = "$matchKey|$type|$minuteKey"

            val p = normPlayerKey(e.player)
            val withPlayer = if (p.isNotEmpty()) "$noPlayer|p:$p" else noPlayer
            return noPlayer to withPlayer
        }

        fun isBetter(newE: MatchEvent, oldE: MatchEvent): Boolean {
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
            val newTs = newE.ts ?: java.time.Instant.EPOCH
            val oldTs = oldE.ts ?: java.time.Instant.EPOCH
            return newTs.isAfter(oldTs)
        }

        // Merge current + new, de-dupe with enrichment updates
        val mergedMap = LinkedHashMap<String, MatchEvent>()
        (current.lastEvents + newEvents).forEach { e ->
            val (noPlayerKey, candidateKey) = keysFor(e)
            // If we already have an older "no player" placeholder, let the richer event overwrite it.
            val k = if (candidateKey != noPlayerKey && mergedMap.containsKey(noPlayerKey)) noPlayerKey else candidateKey

            val existing = mergedMap[k]
            if (existing == null || isBetter(e, existing)) {
                mergedMap[k] = e
            }
        }

        fun sortTs(e: MatchEvent): java.time.Instant = e.ts ?: java.time.Instant.EPOCH

        val ordered = mergedMap.values
            .sortedWith(
                compareBy<MatchEvent>(
                    { parseMinute(it.minute, it.time) ?: Int.MAX_VALUE },
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

        val merged = kept.sortedWith(
            compareBy<MatchEvent>(
                { parseMinute(it.minute, it.time) ?: Int.MAX_VALUE },
                { sortTs(it) }
            )
        )

        val updated = current.copy(lastEvents = merged)
        matchStateStore.put(updated)
        sseHub.publish(matchKey, "state", updated)
        return updated
    }

    fun replaceLiveMatchKeys(matchKeys: List<String>) {
        liveMatchesStore.replaceAll(matchKeys)
    }

    fun publishLiveBoard() {
        val board = getBoardMatches()
        if (board.isNotEmpty()) {
            sseHub.publish("live-board", "live", board)
        } else {
            // fallback for first boot / before board keys are set
            sseHub.publish("live-board", "live", getLiveMatches())
        }
    }
}
