package com.selim.livescores.repository.redis

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class LiveMatchesStore(
    private val redis: StringRedisTemplate
) {
    private val key = "matches:live"

    fun replaceAll(matchKeys: List<String>, ttl: Duration = Duration.ofMinutes(5)) {
        redis.delete(key)
        if (matchKeys.isNotEmpty()) {
            redis.opsForSet().add(key, *matchKeys.toTypedArray())
            redis.expire(key, ttl)
        }
    }

    fun getAll(): Set<String> = redis.opsForSet().members(key) ?: emptySet()
}
