package com.selim.livescores.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Minimal provider-friendly event.
 * Mirrors: /matches/events.json -> data.event[]
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MatchEvent(
    val id: String? = null,          // provider event id
    val event: String? = null,       // GOAL, GOAL_PENALTY, YELLOW_CARD, ...
    val time: String? = null,        // minute as string, ex "10"
    val player: String? = null,

    @JsonProperty("home_away")
    val homeAway: String? = null,    // "h" or "a"

    @JsonProperty("match_id")
    val matchId: String? = null,     // provider match id as string

    val ts: Instant = Instant.now()
) {
    @get:JsonIgnore
    val minute: Int?
        get() = time?.trim()?.toIntOrNull()

}
