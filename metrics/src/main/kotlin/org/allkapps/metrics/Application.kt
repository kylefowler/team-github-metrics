package org.allkapps.metrics

import com.github.ajalt.clikt.core.*
import org.allkapps.metrics.commands.Github

class Metrics : NoOpCliktCommand()

fun main(args: Array<String>) = Metrics()
    .subcommands(
        Github()
            .subcommands(
                Github.Config(),
                Github.RateLimit(),
                Github.TeamPrStats(),
                Github.UserPrStats(),
                Github.TeamReviewParticipation(),
                Github.UserReviewParticipation(),
                Github.Changelog()
            )
    ).main(args)