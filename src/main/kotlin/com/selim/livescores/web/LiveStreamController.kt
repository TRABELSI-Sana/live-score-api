package com.selim.livescores.web

import com.selim.livescores.domain.MatchState
import com.selim.livescores.service.MatchService
import com.selim.livescores.service.StandingsService
import com.selim.livescores.sse.SseHub
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/stream")
class LiveStreamController(
    private val sseHub: SseHub,
    private val matchService: MatchService,
    private val standingsService: StandingsService
) {
    @GetMapping(value = ["/live"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun live(): SseEmitter = sseHub.subscribe("live-board")

    @GetMapping("/board")
    fun board(): List<MatchState> = matchService.getBoardMatches()

    @GetMapping("/competitions/{competitionId}/table", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun table(@PathVariable competitionId: Int): String =
        standingsService.getCompetitionTableJson(competitionId)
}
