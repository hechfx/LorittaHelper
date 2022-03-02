package net.perfectdreams.loritta.helper.utils.tickets

import com.github.benmanes.caffeine.cache.Caffeine
import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.optional
import dev.kord.rest.builder.channel.thread.ThreadModifyBuilder
import dev.kord.rest.json.request.StartThreadRequest
import mu.KotlinLogging
import net.perfectdreams.discordinteraktions.common.components.ButtonClickExecutorDeclaration
import net.perfectdreams.discordinteraktions.common.components.ButtonClickWithDataExecutor
import net.perfectdreams.discordinteraktions.common.components.ComponentContext
import net.perfectdreams.discordinteraktions.common.components.GuildComponentContext
import net.perfectdreams.discordinteraktions.common.entities.User
import net.perfectdreams.loritta.api.messages.LorittaReply
import net.perfectdreams.loritta.helper.LorittaHelperKord
import net.perfectdreams.loritta.helper.i18n.I18nKeysData
import net.perfectdreams.loritta.helper.utils.ComponentDataUtils
import net.perfectdreams.loritta.helper.utils.cache.TicketsCache
import java.util.concurrent.TimeUnit

class CreateTicketButtonExecutor(val m: LorittaHelperKord) : ButtonClickWithDataExecutor {
    companion object : ButtonClickExecutorDeclaration(CreateTicketButtonExecutor::class, "create_ticket") {
        private val logger = KotlinLogging.logger {}
    }

    val recentlyCreatedTickets = Caffeine.newBuilder()
        .expireAfterWrite(5L, TimeUnit.MINUTES)
        .build<Snowflake, Long>()
        .asMap()

    override suspend fun onClick(user: User, context: ComponentContext, data: String) {
        if (context is GuildComponentContext) {
            val ticketSystemTypeData = ComponentDataUtils.decode<TicketSystemTypeData>(data)
            val systemInfo = TicketUtils.getInformationBySystemType(ticketSystemTypeData.systemType)
            val language = systemInfo.getI18nContext(m.languageManager)

            // Avoid users closing and reopening threads constantly
            val lastTicketCreatedAt = recentlyCreatedTickets[user.id]

            if (context.member.roles.contains(Snowflake(341343754336337921L))) { // Desenhistas role
                context.sendEphemeralMessage {
                    // 300 = 5 minutes
                    content = language.get("Você já tem o cargo de desenhistas, você não precisa enviar uma \"Primeira Fan Art\" novamente! Caso queira enviar mais fan arts para a galeria, basta enviar em <#583406099047252044>")
                }
                return
            }

            if (lastTicketCreatedAt != null) {
                context.sendEphemeralMessage {
                    // 300 = 5 minutes
                    content = language.get(
                        I18nKeysData.Tickets.YouAlreadyCreatedATicketRecently(
                            "<:lori_sob:556524143281963008>",
                            "<t:${(lastTicketCreatedAt / 1000) + 300}:R>"
                        )
                    )
                }
                return
            }
            recentlyCreatedTickets[user.id] = System.currentTimeMillis()

            context.sendEphemeralMessage {
                content = language.get(I18nKeysData.Tickets.CreatingATicket)
            }

            val cachedTickets = m.getTicketsCacheBySystemType(ticketSystemTypeData.systemType)
            val alreadyCreatedUserTicketData = cachedTickets.tickets[context.sender.id]
            var ticketThreadId = alreadyCreatedUserTicketData?.id

            // Max username size = 32
            // Max ID length (well it can be bigger): 18
            // So if we do the sum of everything...
            // 3 (beginning) + 32 (username) + 2 (space and "(") + 18 (user ID) + 1 (")")
            // = 56
            // Threads can have at most 100 chars!
            val threadName = "\uD83D\uDCE8 ${user.name} (${user.id.value})"

            if (alreadyCreatedUserTicketData == null) {
                // If it is STILL null, we will create a thread!
                ticketThreadId = m.helperRest.channel.startThread(
                    context.channelId,
                    StartThreadRequest(
                        threadName,
                        systemInfo.archiveDuration,
                        ChannelType.PrivateThread.optional(),
                    ),
                    "Ticket created for <@${user.id.value}>"
                ).id
            }

            if (ticketThreadId == null) {
                logger.warn { "ticketThreadId is null, this should never happen! Invalidating cached ticket ID and retrying..." }
                recentlyCreatedTickets[user.id] = null
                cachedTickets.tickets.remove(context.sender.id)
                onClick(user, context, data)
                return
            }

            // Update thread metadata and name juuuust to be sure
            m.helperRest.channel.patchThread(
                ticketThreadId,
                ThreadModifyBuilder().apply {
                    this.name = threadName
                    this.archived = false
                    this.locked = false // For now let's keep it as not locked to avoid a bug in Discord Mobile related to "You don't have permission!"
                    this.invitable = false
                }.toRequest(),
                "Unarchival request via button by ${user.name}#${user.discriminator} (${user.id.value})"
            )

            // We need to add the user to the thread after it is unarchived!
            m.helperRest.channel.addUserToThread(
                ticketThreadId,
                user.id
            )

            cachedTickets.tickets[user.id] = TicketsCache.DiscordThreadTicketData(ticketThreadId)

            // Only resend the message if the thread was archived or if it is a new thread
            if (systemInfo is TicketUtils.HelpDeskTicketSystemInformation) {
                m.helperRest.channel.createMessage(
                    ticketThreadId
                ) {
                    content = (
                            listOf(
                                LorittaReply(
                                    language.get(I18nKeysData.Tickets.ThreadCreated.Ready),
                                    "<:lori_coffee:727631176432484473>",
                                    mentionUser = true
                                ),
                                LorittaReply(
                                    language.get(I18nKeysData.Tickets.ThreadCreated.QuestionTips("<@&${systemInfo.supportRoleId.value}>")),
                                    "<:lori_coffee:727631176432484473>",
                                    mentionUser = false
                                ),
                                LorittaReply(
                                    "**${
                                        language.get(
                                            I18nKeysData.Tickets.ThreadCreated.PleaseRead(
                                                "<#${systemInfo.faqChannelId.value}>",
                                                "<https://loritta.website/extras>"
                                            )
                                        )
                                    }**",
                                    "<:lori_analise:853052040425766922>",
                                    mentionUser = false
                                ),
                                LorittaReply(
                                    language.get(I18nKeysData.Tickets.ThreadCreated.AfterAnswer),
                                    "<a:lori_pat:706263175892566097>",
                                    mentionUser = false
                                )
                            )
                            )
                        .joinToString("\n")
                        { it.build(context.sender) }
                }
            } else if (systemInfo is TicketUtils.FirstFanArtTicketSystemInformation) {
                m.helperRest.channel.createMessage(
                    ticketThreadId
                ) {
                    content = (
                            listOf(
                                LorittaReply(
                                    "Envie a sua fan art e, caso tenha, envie o processo de criação dela!",
                                    "<:lori_coffee:727631176432484473>",
                                    mentionUser = true
                                ),
                                LorittaReply(
                                    "Após enviado, os <@&${systemInfo.fanArtsManagerRoleId.value}> irão averiguar a sua fan art e, caso ela tenha uma qualidade excepcional, ela será incluida na nossa Galeria de Fan Arts!",
                                    "<:lori_analise:853052040425766922>",
                                    mentionUser = false
                                ),
                            )
                            )
                        .joinToString("\n")
                        { it.build(context.sender) }
                }
            }

            context.sendEphemeralMessage {
                content = language.get(
                    I18nKeysData.Tickets.TicketWasCreated("<#${ticketThreadId}>")
                )
            }
        }
    }
}