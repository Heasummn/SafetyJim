package org.samoxive.safetyjim.discord.commands

import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.PermissionOverride
import net.dv8tion.jda.core.entities.Role
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import org.samoxive.safetyjim.database.MuteEntity
import org.samoxive.safetyjim.database.MutesTable
import org.samoxive.safetyjim.database.SettingsEntity
import org.samoxive.safetyjim.discord.*
import java.awt.Color
import java.util.*

class Mute : Command() {
    override val usages = arrayOf("mute @user [reason] | [time] - mutes the user with specific args. Both arguments can be omitted.")

    override suspend fun run(bot: DiscordBot, event: GuildMessageReceivedEvent, settings: SettingsEntity, args: String): Boolean {
        val messageIterator = Scanner(args)
        val shard = event.jda

        val member = event.member
        val user = event.author
        val message = event.message
        val channel = event.channel
        val guild = event.guild
        val selfMember = guild.selfMember

        if (!member.hasPermission(Permission.MANAGE_ROLES)) {
            message.failMessage("You don't have enough permissions to execute this command! Required permission: Manage Roles")
            return false
        }

        if (args.isEmpty()) {
            return true
        }

        val (searchResult, muteUser) = messageIterator.findUser(message)
        if (searchResult == SearchUserResult.NOT_FOUND || (muteUser == null)) {
            message.failMessage("Could not find the user to mute!")
            return false
        }

        if (searchResult == SearchUserResult.GUESSED) {
            message.askConfirmation(bot, muteUser) ?: return false
        }

        val muteMember = guild.getMember(muteUser)
        val controller = guild.controller

        if (!selfMember.hasPermission(Permission.MANAGE_ROLES, Permission.MANAGE_PERMISSIONS)) {
            message.failMessage("I don't have enough permissions to do that!")
            return false
        }

        if (user == muteUser) {
            message.failMessage("You can't mute yourself, dummy!")
            return false
        }

        if (muteUser == selfMember.user) {
            message.failMessage("Now that's just rude. (I can't mute myself)")
            return false
        }

        val mutedRole = try {
            setupMutedRole(guild)
        } catch (e: Exception) {
            message.failMessage("Could not create a Muted role, do I have enough permissions?")
            return false
        }

        val parsedReasonAndTime = try {
            messageIterator.getTextAndTime()
        } catch (e: InvalidTimeInputException) {
            message.failMessage("Invalid time argument. Please try again.")
            return false
        } catch (e: TimeInputInPastException) {
            message.failMessage("Your time argument was set for the past. Try again.\n" + "If you're specifying a date, e.g. `30 December`, make sure you also write the year.")
            return false
        }

        val (text, expirationDate) = parsedReasonAndTime
        val reason = if (text == "") "No reason specified" else text
        val now = Date()

        val embed = EmbedBuilder()
        embed.setTitle("Muted in " + guild.name)
        embed.setColor(Color(0x4286F4))
        embed.setDescription("You were muted in " + guild.name)
        embed.addField("Reason:", truncateForEmbed(reason), false)
        embed.addField("Muted until", expirationDate?.toString() ?: "Indefinitely", false)
        embed.setFooter("Muted by " + user.getUserTagAndId(), null)
        embed.setTimestamp(now.toInstant())

        muteUser.trySendMessage(embed.build())

        try {
            controller.addSingleRoleToMember(muteMember, mutedRole).await()
            message.successReact()

            val expires = expirationDate != null
            MutesTable.invalidatePreviousUserMutes(guild, muteUser)
            val record = MutesTable.insertMute(
                    MuteEntity(
                            userId = muteUser.idLong,
                            moderatorUserId = user.idLong,
                            guildId = guild.idLong,
                            muteTime = now.time / 1000,
                            expireTime = if (expirationDate == null) 0 else expirationDate.time / 1000,
                            reason = reason,
                            expires = expires,
                            unmuted = false
                    )
            )

            message.createModLogEntry(shard, settings, muteUser, reason, "mute", record.id, expirationDate, true)
            channel.trySendMessage("Muted ${muteUser.getUserTagAndId()} ${getExpirationTextInChannel(expirationDate)}")
        } catch (e: Exception) {
            message.failMessage("Could not mute the specified user. Do I have enough permissions?")
        }

        return false
    }

    companion object {

        suspend fun setupMutedRole(guild: Guild): Role {
            val controller = guild.controller
            val channels = guild.textChannels
            val roleList = guild.roles
            var mutedRole: Role? = null

            for (role in roleList) {
                if (role.name == "Muted") {
                    mutedRole = role
                    break
                }
            }

            if (mutedRole == null) {
                // Muted role doesn't exist at all, so we need to create one
                // and create channel overrides for the role
                mutedRole = controller.createRole()
                        .setName("Muted")
                        .setPermissions(
                                Permission.MESSAGE_READ,
                                Permission.MESSAGE_HISTORY,
                                Permission.VOICE_CONNECT
                        )
                        .await()

                for (channel in channels) {
                    channel.createPermissionOverride(mutedRole)
                            .setDeny(
                                    Permission.MESSAGE_WRITE,
                                    Permission.MESSAGE_ADD_REACTION,
                                    Permission.VOICE_SPEAK
                            )
                            .await()
                }
            }

            for (channel in channels) {
                var override: PermissionOverride? = null
                for (channelOverride in channel.rolePermissionOverrides) {
                    if (channelOverride.role == mutedRole) {
                        override = channelOverride
                        break
                    }
                }

                // This channel is either created after we created a Muted role
                // or its permissions were played with, so we should set it straight
                if (override == null) {
                    channel.createPermissionOverride(mutedRole)
                            .setDeny(
                                    Permission.MESSAGE_WRITE,
                                    Permission.MESSAGE_ADD_REACTION,
                                    Permission.VOICE_SPEAK
                            )
                            .await()
                }
            }

            // return the found or created muted role so command can use it
            return mutedRole!!
        }
    }
}
