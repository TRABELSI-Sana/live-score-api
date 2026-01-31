package com.selim.livescores

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class LiveScoresApplication

fun main(args: Array<String>) {
    runApplication<LiveScoresApplication>(*args)
}
