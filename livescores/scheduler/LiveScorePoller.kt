package com.selim.livescores.scheduler

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.selim.livescores.domain.MatchEvent
import com.selim.livescores.domain.MatchState
import com.selim.livescores.repository.redis.EventDedupStore
import com.selim.livescores.provider.livescore.LiveScoreApiClient
import com.selim.livescores.service.MatchService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Polls live-score-api.com and updates Redis + pushes SSE.
 *
 * Strategy (starter plan 14,500 req/day):
 * - live matches every 60s
 * - events for live matches every 90s
 */
@Component
class LiveScorePoller(
    private val api: LiveScoreApiClient,
    private val objectMapper: ObjectMapper,
    private val matchService: MatchService,
    private val dedup: EventDedupStore,
) {

    private val eventsCursor = AtomicInteger(0)

    @Volatile private var apiDisabledUntil: Instant? = null
    @Volatile private var apiDisabledReason: String? = null

    private fun isApiDisabled(now: Instant = Instant.now()): Boolean {
        val until = apiDisabledUntil ?: return false
        return now.isBefore(until)
    }

    private fun disableApiFor(duration: Duration, reason: String) {
        apiDisabledUntil = Instant.now().plus(duration)
        apiDisabledReason = reason
    }

    private fun clearApiDisable() {
        apiDisabledUntil = null
        apiDisabledReason = null
    }

    private inline fun <T> guardedApiCall(block: () -> T): T? {
        if (isApiDisabled()) return null
        return try {
            val res = block()
            // If we succeed after a previous disable, clear it.
            if (apiDisabledUntil != null) clearApiDisable()
            res
        } catch (e: LiveScoreApiClient.QuotaExceededException) {
            // When the provider says quota exceeded, stop hitting it for a while.
            disableApiFor(Duration.ofHours(6), "quota_exceeded")
            null
        } catch (e: org.springframework.web.client.HttpClientErrorException.Unauthorized) {
            // Bad key/secret or access not enabled. Pause long to avoid hammering.
            disableApiFor(Duration.ofHours(24), "unauthorized")
            null
        } catch (e: Exception) {
            // Network / parsing / transient errors: short pause.
            disableApiFor(Duration.ofMinutes(5), "transient_error")
            null
        }
    }

    private fun <T> List<T>.chunkFromCursor(max: Int): List<T> {
        if (this.isEmpty()) return emptyList()
        if (max <= 0) return emptyList()
        val start = (eventsCursor.getAndIncrement() % this.size).coerceAtLeast(0)
        val end = (start + max).coerceAtMost(this.size)
        return if (start < end) this.subList(start, end) else this.take(max)
    }

    @Scheduled(fixedDelay = 60_000)
    fun pollLiveMatches() {
        val previousLiveKeys = matchService.getLiveMatchKeys()
        val previousBoardKeys = matchService.getBoardMatchKeys()

        val json = guardedApiCall { api.getLiveMatchesJson() } ?: return
        val resp = try {
            objectMapper.readValue(json, LiveMatchesResponse::class.java)
        } catch (e: Exception) {
            // Bad payload / schema change => pause a bit and exit.
            disableApiFor(Duration.ofMinutes(10), "json_parse_error")
            return
        }
        if (resp.success != true) return

        // Normalize keys BEFORE we build the live/board key lists, otherwise we may keep both
        // `ls-<match_id>` and `ls-<fixture_id>` and render duplicates.
        val providerMatches = resp.data?.match.orEmpty()
            .map { matchService.normalizeForKeys(it, previousBoardKeys) }

        // Live = à poller (events), Finished = à afficher sans polling
        val liveMatches = providerMatches.filter {
            it.status == "IN PLAY" || it.status == "ADDED TIME" || it.status == "HALF TIME BREAK"
        }
        val finishedMatches = providerMatches.filter { it.status == "FINISHED" }

        fun String?.ok() = !this.isNullOrBlank()

        val newLiveKeys = liveMatches.map { it.matchKey }.filter { it.ok() }.distinct()
        val newFinishedKeys = finishedMatches.map { it.matchKey }.filter { it.ok() }.distinct()

        // Si aucun match renvoyé du provider => on freeze les anciens live (FT) mais on garde le board
        if (newLiveKeys.isEmpty() && newFinishedKeys.isEmpty()) {
            previousLiveKeys.forEach { matchService.markAsFinished(it) }
            matchService.replaceLiveMatchKeys(emptyList())
            // ne pas vider le board : on garde l’existant
            matchService.publishLiveBoard()
            return
        }

        // Upsert les live + finished (important: score final + status)
        liveMatches.forEach { matchService.upsertFromProvider(it, previousBoardKeys) }
        finishedMatches.forEach { matchService.upsertFromProvider(it, previousBoardKeys) }

        // Mettre à jour les live keys (pour pollEvents)
        matchService.replaceLiveMatchKeys(newLiveKeys)

        // Si un match était live avant mais n’est plus live maintenant => freeze FT
        val disappearedFromLive = previousLiveKeys.filter { it !in newLiveKeys.toSet() }
        disappearedFromLive.forEach { matchService.markAsFinished(it) }

        // Normaliser ceux explicitement FINISHED (au cas où)
        newFinishedKeys.forEach { matchService.markAsFinished(it) }

        // Construire board keys: live + finished + anciens board + nouveaux finis
        val boardKeys = (newLiveKeys + newFinishedKeys + previousBoardKeys + disappearedFromLive).distinct()
        matchService.replaceBoardMatchKeys(boardKeys)

        matchService.publishLiveBoard()
    }

    @Scheduled(fixedDelay = 60_000)
    fun pollEvents() {
        val liveMatches = matchService.getLiveMatches()
            .filter { it.status == "IN PLAY" || it.status == "ADDED TIME" || it.status == "HALF TIME BREAK" }

        if (liveMatches.isEmpty()) return
        if (isApiDisabled()) return

        // ✅ Optimization: do not poll events for ALL live matches every tick.
        // Pick a rotating slice to reduce requests and smooth quota usage.
        val toPoll = liveMatches.chunkFromCursor(max = 8)

        var anyUpdate = false

        toPoll.forEach { state ->
            val providerId = state.id ?: return@forEach

            val json = guardedApiCall { api.getMatchEventsJson(providerId) } ?: return@forEach
            val resp = try {
                objectMapper.readValue(json, MatchEventsResponse::class.java)
            } catch (e: Exception) {
                disableApiFor(Duration.ofMinutes(10), "json_parse_error")
                return@forEach
            }
            if (resp.success != true) return@forEach

            val matchKey = state.matchKey
            val newEvents = mutableListOf<MatchEvent>()

            resp.data?.event.orEmpty()
                .filter { !it.event.isNullOrBlank() && it.event != "." }
                .forEach { e ->
                    // Provider `id` is not stable between polls; use a composite stable key.
                    // Important: do NOT include player name in the stable key, because provider may first send "" then later send the real name.
                    val stableId = listOf(matchKey, e.time, e.event, e.homeAway)
                        .joinToString(separator = "|") { (it ?: "").trim().lowercase() }

                    // 🔧 Prod fix: our Redis dedup store can get out of sync with the match state (different TTLs / restarts),
                    // and it also blocks "enrichment" updates (player empty -> player name later).
                    // Allow the event through if:
                    //  - it's new according to dedup OR
                    //  - the current state doesn't contain this event yet (dedup desync) OR
                    //  - the current state has the same event but with blank player, and the provider now sends a player name (upgrade).
                    val hasSameEventInState = state.lastEvents.any {
                        val k = listOf(matchKey, it.time, it.event, it.homeAway)
                            .joinToString("|") { v -> (v ?: "").trim().lowercase() }
                        k == stableId
                    }

                    val isUpgrade = state.lastEvents.any {
                        val k = listOf(matchKey, it.time, it.event, it.homeAway)
                            .joinToString("|") { v -> (v ?: "").trim().lowercase() }
                        k == stableId && it.player.isNullOrBlank() && !e.player.isNullOrBlank()
                    }

                    val isNewByDedup = dedup.isNew(matchKey, stableId)

                    if (!isNewByDedup && hasSameEventInState && !isUpgrade) return@forEach

                    newEvents.add(e)
                }

            if (newEvents.isNotEmpty()) {
                matchService.appendEvents(matchKey, newEvents)
                anyUpdate = true
            }
        }

        if (anyUpdate) {
            matchService.publishLiveBoard()
        }
    }

}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LiveMatchesResponse(
    val success: Boolean? = null,
    val data: LiveMatchesData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LiveMatchesData(
    val match: List<MatchState> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MatchEventsResponse(
    val success: Boolean? = null,
    val data: MatchEventsData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MatchEventsData(
    val event: List<MatchEvent> = emptyList()
)