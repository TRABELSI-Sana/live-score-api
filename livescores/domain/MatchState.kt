package com.selim.livescores.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty


@JsonIgnoreProperties(ignoreUnknown = true)
data class MatchState(
    val id: Long? = null,

    /**
     * Provider fixture id (planned match). When present, we use it to compute [matchKey]
     * so planned and live representations merge into the same Redis key.
     */
    @JsonProperty("fixture_id")
    val fixtureId: Long? = null,

    val scheduled: String? = null,
    val status: String? = null,
    val time: String? = null,
    val competition: Competition? = null,
    val home: Team? = null,
    val away: Team? = null,
    val scores: Scores? = null,

    val lastEvents: List<MatchEvent> = emptyList()
) {
    @get:JsonIgnore
    val matchKey: String
        get() = (fixtureId ?: id)?.let { "ls-$it" } ?: "ls-unknown"
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Competition(
    val id: Long? = null,
    val name: String? = null,
    val country: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Team(
    val id: Long? = null,
    val name: String? = null,
    val logo: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Scores(
    val score: String? = null,
    val ht_score: String? = null,
    val ft_score: String? = null,
    val et_score: String? = null,
    val ps_score: String? = null
)