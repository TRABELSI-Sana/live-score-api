package com.selim.livescores.service

import com.selim.livescores.provider.livescore.LiveScoreApiClient
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class StandingsService(
    private val liveScoreApiClient: LiveScoreApiClient,
    private val redis: StringRedisTemplate
) {
    fun getCompetitionTableJson(competitionId: Int): String {
        val cacheKey = cacheKey(competitionId)
        val cached = redis.opsForValue().get(cacheKey)
        if (!cached.isNullOrBlank()) return cached

        val payload = liveScoreApiClient.getCompetitionTableJson(competitionId)
        redis.opsForValue().set(cacheKey, payload, Duration.ofMinutes(5))
        return payload
    }

    fun invalidateCompetition(competitionId: Long) {
        redis.delete(cacheKey(competitionId.toString()))
    }

    private fun cacheKey(competitionId: Int) = cacheKey(competitionId.toString())

    private fun cacheKey(competitionId: String) = "livescore:standings:$competitionId"
}
