package com.selim.livescores.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.selim.livescores.domain.MatchState
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisConfig {
    @Bean
    fun redisObjectMapper(): ObjectMapper =
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Bean
    fun matchStateRedisTemplate(
        connectionFactory: RedisConnectionFactory, redisObjectMapper: ObjectMapper
    ): RedisTemplate<String, MatchState> {
        val keySerializer = StringRedisSerializer()
        val valueSerializer = Jackson2JsonRedisSerializer(redisObjectMapper, MatchState::class.java)
        return RedisTemplate<String, MatchState>().apply {
            this.connectionFactory = connectionFactory
            this.keySerializer = keySerializer
            this.valueSerializer = valueSerializer
            this.hashKeySerializer = keySerializer
            this.hashValueSerializer = valueSerializer
            afterPropertiesSet()
        }
    }

}