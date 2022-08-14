package net.perfectdreams.loritta.helper.utils.slash

import net.perfectdreams.discordinteraktions.common.builder.message.embed
import net.perfectdreams.discordinteraktions.common.commands.ApplicationCommandContext
import net.perfectdreams.discordinteraktions.common.commands.options.ApplicationCommandOptions
import net.perfectdreams.discordinteraktions.common.commands.options.SlashCommandArguments
import net.perfectdreams.discordinteraktions.common.utils.footer
import net.perfectdreams.loritta.helper.LorittaHelperKord
import net.perfectdreams.loritta.helper.tables.StartedSupportSolicitations
import net.perfectdreams.loritta.helper.tables.TicketMessagesActivity
import net.perfectdreams.loritta.helper.utils.tickets.TicketUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class StatsTicketsExecutor(helper: LorittaHelperKord) : HelperSlashExecutor(helper, PermissionLevel.HELPER) {
    inner class Options : ApplicationCommandOptions() {
        val system = string("system", "Sistema") {
            for (type in TicketUtils.TicketSystemType.values()) {
                choice(
                    when (type) {
                        TicketUtils.TicketSystemType.HELP_DESK_PORTUGUESE -> "Loritta Help Desk (Português)"
                        TicketUtils.TicketSystemType.HELP_DESK_ENGLISH -> "Loritta Help Desk (English)"
                        TicketUtils.TicketSystemType.FIRST_FAN_ARTS_PORTUGUESE -> "Primeira Fan Art (Português)"
                        TicketUtils.TicketSystemType.SPARKLYPOWER_HELP_DESK_PORTUGUESE -> "SparklyPower Help Desk"
                    },
                    type.name
                )
            }
        }

        val filter = optionalString("filter", "Filtro de data") {
            choice("Últimos 7 dias", "7")
            choice("Últimos 14 dias", "14")
            choice("Últimos 30 dias", "30")
            choice("Últimos 90 dias", "90")
            choice("Últimos 365 dias", "365")
        }
    }

    override val options = Options()

    override suspend fun executeHelper(context: ApplicationCommandContext, args: SlashCommandArguments) {
        val system = TicketUtils.TicketSystemType.valueOf(args[options.system])
        val filterDay = args[options.filter]

        // When using Instant.MIN, this happened:
        // "DefaultDispatcher-worker-2" java.time.DateTimeException: Invalid value for EpochDay (valid values -365243219162 - 365241780471): -365243219528
        var since = Instant.ofEpochMilli(0)

        if (filterDay != null)
            since = Instant.now().minusSeconds(filterDay.toLong() * 86400)

        val result = transaction(helper.databases.helperDatabase) {
            val resultCount = TicketMessagesActivity.supportSolicitationId.countDistinct()

            val currentBanStatus = TicketMessagesActivity
                .innerJoin(StartedSupportSolicitations)
                .slice(TicketMessagesActivity.userId, resultCount)
                .select {
                    TicketMessagesActivity.timestamp greaterEq since and (StartedSupportSolicitations.systemType eq system)
                }
                .groupBy(TicketMessagesActivity.userId)
                .toList()

            currentBanStatus
                .map {
                    UserStatsResult(
                        it[TicketMessagesActivity.userId],
                        it[resultCount]
                    )
                }
        }


        context.sendMessage {
            content = "Sistema: $system"

            embed {
                title = "Ranking de Pessoas Tagarelas em Tickets Respondidos"

                description = buildString {
                    for ((index, userStats) in result
                        .sortedByDescending { it.ticketsReplied }
                        .take(25)
                        .withIndex()
                    ) {
                        append("**${index + 1}.** <@${userStats.userId}> - ${userStats.ticketsReplied} tickets respondidos")
                        append("\n")
                    }
                }

                footer("Burocracia my beloved")
            }
        }
    }

    private class UserStatsResult(
        val userId: Long,
        val ticketsReplied: Long
    )
}
