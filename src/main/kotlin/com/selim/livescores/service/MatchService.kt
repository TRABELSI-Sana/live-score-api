package com.selim.livescores.service

import com.selim.livescores.domain.MatchEvent
import com.selim.livescores.domain.MatchState
import com.selim.livescores.domain.MatchStatus
import com.selim.livescores.repository.redis.MatchStateStore
import com.selim.livescores.repository.redis.LiveMatchesStore
import com.selim.livescores.sse.SseHub
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class MatchService(
    private val matchStateStore: MatchStateStore,
    private val liveMatchesStore: LiveMatchesStore,
    private val sseHub: SseHub,
    private val redis: StringRedisTemplate,
    private val standingsService: StandingsService
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

        val today = LocalDate.now().toString()
        val filtered = raw.filter { m ->
            if (m.status == MatchStatus.NOT_STARTED) {
                val date = m.fixtureDate?.trim()
                date.isNullOrBlank() || date == today
            } else {
                true
            }
        }

        val deduped = LinkedHashMap<String, MatchState>()
        filtered.forEach { m ->
            val k = identityKey(m)
            val existing = deduped[k]
            deduped[k] = if (existing == null) m else chooseBetter(existing, m)
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
        if (current.status == MatchStatus.FINISHED) return current
        val updated = current.copy(status = MatchStatus.FINISHED, time = "FT")
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
            status = MatchStatus.UNKNOWN,
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

        val mergedEvents = when {
            current == null -> providerMatch.lastEvents
            current.lastEvents.isEmpty() && providerMatch.lastEvents.isNotEmpty() -> providerMatch.lastEvents
            else -> current.lastEvents
        }

        val merged = normalized.copy(
            lastEvents = mergedEvents
        )

        val scoresChanged = current?.scores != merged.scores
        if (scoresChanged) {
            merged.competition?.id?.let { standingsService.invalidateCompetition(it) }
        }

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

        return if (existing?.fixtureId != null) {
            providerMatch.copy(fixtureId = existing.fixtureId, fixtureDate = existing.fixtureDate)
        } else {
            providerMatch
        }
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

        val deduplicator = EventDeduplicator(matchKey)
        val merged = deduplicator.merge(current.lastEvents, newEvents, keepLast)

        if (merged == current.lastEvents) {
            return current
        }

        val updated = current.copy(lastEvents = merged)
        matchStateStore.put(updated)
        sseHub.publish(matchKey, "state", updated)
        return updated
    }

    fun replaceEvents(matchKey: String, events: List<MatchEvent>): MatchState {
        val current = getOrInitState(matchKey)
        val updated = current.copy(lastEvents = events)
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

    private fun identityKey(m: MatchState): String {
        // Prefer fixtureId when present (stable across the day).
        m.fixtureId?.let { return "fx:$it" }
        val c = m.competition?.id?.toString() ?: "?"
        val h = m.home?.id?.toString() ?: "?"
        val a = m.away?.id?.toString() ?: "?"
        val t = (m.scheduled ?: "").trim()
        return "cmp:$c|h:$h|a:$a|t:$t"
    }

    private fun chooseBetter(a: MatchState, b: MatchState): MatchState {
        // Prefer the one that looks “more advanced” (later minute) or has richer data.
        val aMin = TimeParser.parseMinute(a.time) ?: -1
        val bMin = TimeParser.parseMinute(b.time) ?: -1
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
}
