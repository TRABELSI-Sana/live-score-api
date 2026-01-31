package com.selim.livescores.repository.redis

import com.selim.livescores.domain.MatchState
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class MatchStateStore(private val matchStateRedisTemplate: RedisTemplate<String, MatchState>) {
    private fun key(matchKey: String) = "match:state:$matchKey"

    fun get(matchKey: String): MatchState? =
        matchStateRedisTemplate.opsForValue().get(key(matchKey))

    fun put(state: MatchState, ttl: Duration? = null) {
        val computedTtl = ttl ?: when (state.status) {
            "NOT STARTED" -> Duration.ofHours(24)     // fixtures: garder toute la journée
            "IN PLAY", "ADDED TIME", "HALF TIME BREAK" -> Duration.ofHours(6)
            "FINISHED" -> Duration.ofHours(48)        // garder l’historique un peu
            else -> Duration.ofHours(12)
        }

        matchStateRedisTemplate.opsForValue().set(key(state.matchKey), state, computedTtl)
    }
}
