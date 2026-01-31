package com.selim.livescores.provider.livescore

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.LocalDate
import java.time.ZoneId

@Component
class LiveScoreApiClient(
    @Value("\${livescore.base-url}") private val baseUrl: String,
    @Value("\${livescore.key}") private val key: String,
    @Value("\${livescore.secret}") private val secret: String,
    @Value("\${livescore.competition-ids:}") private val competitionIds: String,
    private val redisTemplate: StringRedisTemplate,
    @Value("\${livescore.quota-per-day:14500}") private val quotaPerDay: Long
) {

    private val client = RestClient.builder()
        .baseUrl(baseUrl)
        .build()
    fun getFixturesTodayJson(competitionId: Int): String {
        val uri = buildString {
            append("/api-client/fixtures/list.json")
            append("?key=").append(key)
            append("&secret=").append(secret)
            append("&competition_id=").append(competitionId)
            append("&date=").append(LocalDate.now(ZoneId.of("UTC")).toString())
        }
        if (!tryConsumeQuota()) throw QuotaExceededException()
        return client.get().uri(uri).retrieve().body(String::class.java)!!
    }
    fun getLiveMatchesJson(): String {
        val uri = buildString {
            append("/api-client/matches/live.json")
            append("?key=").append(key)
            append("&secret=").append(secret)

            competitionIds.takeIf { it.isNotBlank() }?.let {
                append("&competition_id=").append(it) // ex: "42,343,427"
            }
        }
        if (!tryConsumeQuota()) throw QuotaExceededException()
        return client.get().uri(uri).retrieve().body(String::class.java)!!
    }

    fun getMatchEventsJson(matchId: Long): String {
        val uri = "/api-client/scores/events.json?key=$key&secret=$secret&id=$matchId"
        if (!tryConsumeQuota()) throw QuotaExceededException()
        return client.get().uri(uri).retrieve().body(String::class.java)!!
    }

    fun competitionIdsList(): List<Int> =
        competitionIds
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }

    private fun tryConsumeQuota(): Boolean {
        val key = quotaKeyUtc()

        val current = redisTemplate.opsForValue().get(key)?.toLongOrNull() ?: 0L
        if (current >= quotaPerDay) return false

        val next = redisTemplate.opsForValue().increment(key) ?: (current + 1)
        // set TTL on first hit of the day (keep 2 days for safety)
        if (next == 1L) {
            redisTemplate.expire(key, java.time.Duration.ofDays(2))
        }
        return next <= quotaPerDay
    }

    private fun quotaKeyUtc() = "livescore:quota:${LocalDate.now(ZoneId.of("UTC"))}"

    class QuotaExceededException : RuntimeException("LiveScore daily quota exceeded")
}