package com.selim.livescores.domain

object MatchStatus {
    const val NOT_STARTED = "NOT STARTED"
    const val IN_PLAY = "IN PLAY"
    const val ADDED_TIME = "ADDED TIME"
    const val HALF_TIME_BREAK = "HALF TIME BREAK"
    const val FINISHED = "FINISHED"
    const val UNKNOWN = "UNKNOWN"

    private val LIVE = setOf(IN_PLAY, ADDED_TIME, HALF_TIME_BREAK)

    fun isLive(status: String?): Boolean = status != null && LIVE.contains(status)
    fun isFinished(status: String?): Boolean = status == FINISHED
}
