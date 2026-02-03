package com.selim.livescores.scheduler

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.selim.livescores.domain.Competition
import com.selim.livescores.domain.MatchState
import com.selim.livescores.domain.MatchStatus
import com.selim.livescores.domain.Scores
import com.selim.livescores.domain.Team
import com.selim.livescores.provider.livescore.LiveScoreApiClient
import com.selim.livescores.service.MatchService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class FixturesPoller(
    private val api: LiveScoreApiClient,
    private val objectMapper: ObjectMapper,
    private val matchService: MatchService
) {
    // les matchs planifiés pour aujourd’hui
    @Scheduled(cron = "0 5 6 * * *") // 06:05 tous les jours
    fun pollFixturesToday() {
        val ids = api.competitionIdsList()

        if (ids.isEmpty()) return

        val plannedStates = mutableListOf<MatchState>()

        ids.forEach { compId ->
            val json = api.getFixturesTodayJson(compId)
            val resp = objectMapper.readValue(json, FixturesResponse::class.java)
            if (resp.success != true) return@forEach

            resp.data?.fixtures.orEmpty().forEach { f ->
                plannedStates += MatchState(
                    id = null,                      // pas encore de match_id live
                    fixtureId = f.id,
                    scheduled = f.time?.take(5),    // "19:30"
                    status = MatchStatus.NOT_STARTED,
                    time = null,
                    competition = Competition(f.competition?.id, f.competition?.name, f.country?.name),
                    home = Team(f.home?.id, f.home?.name, f.home?.logo),
                    away = Team(f.away?.id, f.away?.name, f.away?.logo),
                    scores = Scores(score = ""),    // avant match: vide
                    lastEvents = emptyList()
                )
            }
        }

        if (plannedStates.isEmpty()) return

        // upsert dans Redis
        plannedStates.forEach { matchService.upsertFromProvider(it) }

        // ajouter au board keys (sans toucher les live keys)
        val newKeys = plannedStates.map { it.matchKey }.filter { it.isNotBlank() }.distinct()
        val boardKeys = (matchService.getBoardMatchKeys() + newKeys).distinct()
        matchService.replaceBoardMatchKeys(boardKeys)

        // push SSE board
        matchService.publishLiveBoard()
    }
}

/** DTO fixtures minimal */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FixturesResponse(
    val success: Boolean? = null,
    val data: FixturesData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FixturesData(
    val fixtures: List<Fixture> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Fixture(
    val id: Long? = null,
    val time: String? = null,
    val competition: FixtureCompetition? = null,
    val country: Country? = null,
    val home: FixtureTeam? = null,
    val away: FixtureTeam? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Country(val name: String? = null, val id: Long? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class FixtureCompetition(val id: Long? = null, val name: String? = null, val country: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FixtureTeam(val id: Long? = null, val name: String? = null, val logo: String? = null)
