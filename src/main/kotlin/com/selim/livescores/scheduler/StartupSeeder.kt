package com.selim.livescores.scheduler

import com.selim.livescores.service.MatchService
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class StartupSeeder(
    private val livePoller: LiveScorePoller,
    private val fixturePoller: FixturesPoller
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        // seed des matchs planifiés pour aujourd’hui
        fixturePoller.pollFixturesToday()

        // seed des matchs en direct
        livePoller.pollLiveMatches()
    }
}