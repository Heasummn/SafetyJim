package org.samoxive.safetyjim.discord.commands

import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import org.samoxive.safetyjim.database.SettingsEntity
import org.samoxive.safetyjim.database.SoftbanEntity
import org.samoxive.safetyjim.database.SoftbansTable
import org.samoxive.safetyjim.discord.*
import java.awt.Color
import java.util.*

class Softban : Command() {
    override val usages = arrayOf("softban @user [reason] | [messages to delete (days)] - softbans the user with the specified args.")

    override suspend fun run(bot: DiscordBot, event: GuildMessageReceivedEvent, settings: SettingsEntity, args: String): Boolean {
        val messageIterator = Scanner(args)
        val shard = event.jda

        val member = event.member
        val user = event.author
        val message = event.message
        val channel = event.channel
        val guild = event.guild
        val selfMember = guild.selfMember

        if (!member.hasPermission(Permission.BAN_MEMBERS)) {
            message.failMessage("You don't have enough permissions to execute this command! Required permission: Ban Members")
            return false
        }

        if (args.isEmpty()) {
            return true
        }

        val (searchResult, softbanUser) = messageIterator.findUser(message, isForBan = true)
        if (searchResult == SearchUserResult.NOT_FOUND || (softbanUser == null)) {
            message.failMessage("Could not find the user to softban!")
            return false
        }

        if (searchResult == SearchUserResult.GUESSED) {
            message.askConfirmation(bot, softbanUser) ?: return false
        }

        val softbanMember = guild.getMember(softbanUser)
        val controller = guild.controller

        if (!selfMember.hasPermission(Permission.BAN_MEMBERS)) {
            message.failMessage("I don't have enough permissions to do that!")
            return false
        }

        if (user == softbanUser) {
            message.failMessage("You can't softban yourself, dummy!")
            return false
        }

        if (softbanMember != null && !softbanMember.isBannableBy(selfMember)) {
            message.failMessage("I don't have enough permissions to do that!")
            return false
        }

        val arguments = messageIterator.seekToEnd()
        val argumentsSplit = arguments.split("|").toTypedArray()
        var reason = argumentsSplit[0]
        reason = if (reason == "") "No reason specified" else reason.trim()
        var timeArgument: String? = null

        if (argumentsSplit.size > 1) {
            timeArgument = argumentsSplit[1]
        }

        val days = if (timeArgument != null) {
            try {
                timeArgument.trim().toInt()
            } catch (e: NumberFormatException) {
                message.failMessage("Invalid day count, please try again.")
                return false
            }
        } else {
            1
        }

        if (days < 1 || days > 7) {
            message.failMessage("The amount of days must be between 1 and 7.")
            return false
        }

        val now = Date()

        val embed = EmbedBuilder()
        embed.setTitle("Softbanned from " + guild.name)
        embed.setColor(Color(0x4286F4))
        embed.setDescription("You were softbanned from " + guild.name)
        embed.addField("Reason:", truncateForEmbed(reason), false)
        embed.setFooter("Softbanned by " + user.getUserTagAndId(), null)
        embed.setTimestamp(now.toInstant())

        softbanUser.trySendMessage(embed.build())

        try {
            val auditLogReason = "Softbanned by ${user.getUserTagAndId()} - $reason"
            controller.ban(softbanUser, days, auditLogReason).await()
            controller.unban(softbanUser).await()

            val record = SoftbansTable.insertSoftban(
                    SoftbanEntity(
                            userId = softbanUser.idLong,
                            moderatorUserId = user.idLong,
                            guildId = guild.idLong,
                            softbanTime = now.time / 1000,
                            deleteDays = days,
                            reason = reason
                    )
            )

            message.createModLogEntry(shard, settings, softbanUser, reason, "softban", record.id, null, false)
            channel.trySendMessage("Softbanned " + softbanUser.getUserTagAndId())
        } catch (e: Exception) {
            message.failMessage("Could not softban the specified user. Do I have enough permissions?")
        }

        return false
    }
}
