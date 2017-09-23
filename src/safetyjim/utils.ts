import * as Discord from 'discord.js';
import { Shard } from './shard';

export function findShardIdFromGuildId(guildId: string, shardCount: number): number {
    // (guild_id >> 22) % num_shards == shard_id

    let id = parseInt(guildId);
    return (id >> 22) % shardCount;
}

export function getDefaultChannel(guild: Discord.Guild): Discord.TextChannel {
    for (let [id, channel] of guild.channels) {
        if (channel.permissionsFor(guild.me).has('SEND_MESSAGES') && channel.type === 'text') {
            return channel as Discord.TextChannel;
        }
    }

    return guild.channels.first() as Discord.TextChannel;
}

export async function sendMessage(channel: Discord.Channel, message: string | Discord.MessageOptions): Promise<void> {
    let textChannel = channel as Discord.TextChannel;
    try {
        await textChannel.send(message);
    } catch (e) {
        this.log.warn(`Could not send a message in guild "${textChannel.guild.name}"`);
    }
}

export async function failReact(msg: Discord.Message): Promise<void> {
    try {
        await msg.react('322698553980092417');
    } catch (e) {
        this.log.warn(`Could not react with fail emoji in guild "${msg.guild.name}"`);
    }

    return;
}

export async function successReact(msg: Discord.Message): Promise<void> {
    try {
        await msg.react('322698554294534144');
    } catch (e) {
        this.log.warn(`Could not react with success emoji in guild "${msg.guild.name}"`);
    }

    return;
}

export async function createModLogEntry(msg: Discord.Message, member: Discord.GuildMember,
                                        reason: string, action: string, id: number,
                                        parsedTime?: number): Promise<void> {
    let colors = {
        ban: 0xFF2900,
        kick: 0xFF9900,
        warn: 0xFFEB00,
        mute: 0xFFFFFF,
        softban: 0xFF55DD,
    };

    let actionText = {
        ban: 'Ban',
        softban: 'Softban',
        kick: 'Kick',
        warn: 'Warn',
        mute: 'Mute',
    };

    let ModLogActive = await this.database.getGuildSetting(msg.guild, 'modlogactive');
    let prefix = await this.database.getGuildSetting(msg.guild, 'prefix');

    if (!ModLogActive || ModLogActive === 'false') {
        return;
    }

    let ModLogChannelID = await this.database.getGuildSetting(msg.guild, 'modlogchannelid');

    if (!this.client.channels.has(ModLogChannelID) ||
        this.client.channels.get(ModLogChannelID).type !== 'text') {
        // tslint:disable-next-line:max-line-length
        await this.sendMessage(msg.channel, `Invalid mod log channel in guild configuration, set a proper one via \`${prefix} settings\` command.`);
        return;
    }

    let logChannel = this.client.channels.get(ModLogChannelID) as Discord.TextChannel;

    let expires = parsedTime != null;

    let embed = {
        color: colors[action],
        fields: [
            { name: 'Action:', value: `${actionText[action]} - #${id}`, inline: false },
            { name: 'User:', value: `${member.user.tag} (${member.id})`, inline: false },
            { name: 'Reason:', value: reason, inline: false },
            { name: 'Responsible Moderator:', value: `${msg.author.tag} (${msg.author.id})`, inline: false },
            { name: 'Channel', value: msg.channel.toString(), inline: false },
        ],
        timestamp: new Date(),
    };

    if (expires) {
        let value = parsedTime ? new Date(parsedTime).toString() : 'Indefinitely';
        let untilText: string;

        switch (action) {
            case 'ban':
                untilText = 'Banned until';
                break;
            case 'mute':
                untilText = 'Muted until';
                break;
            default:
                break;
        }

        embed.fields.push({ name: untilText, value, inline: false });
    }

    try {
        await this.sendMessage(logChannel, { embed });
    } catch (e) {
        await this.sendMessage(msg.channel, 'Could not create a mod log entry!');
    }

    return;
}

export function getShardString(shard: Shard): string {
    return `[${shard.shardId + 1}, ${shard.config.jim.shard_count}]`;
}
