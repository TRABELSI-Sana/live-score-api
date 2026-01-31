package com.selim.livescores.repository.redis

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class EventDedupStore(
    private val redis: StringRedisTemplate
) {
    private fun key(matchKey: String) = "match:events:seen:$matchKey"

    fun isNew(matchKey: String, eventId: String, ttl: Duration = Duration.ofHours(12)): Boolean {
        val added = redis.opsForSet().add(key(matchKey), eventId) ?: 0L
        if (added > 0) {
            redis.expire(key(matchKey), ttl)
            return true
        }
        return false
    }
}
