# Changelog

## 0.3.11

- Show shared item tooltips in Discord at full size without the black area below and beside them, including long tooltips that Wynntils shrinks to fit a small game window.
- Copy shared item tooltip images out of the graphics card faster, so capturing a preview holds up the game a little less.

## 0.3.10

- Keep guild chat forwarding and rank formatting when nickname hovers use alternate possessive or username wording.
- Move bank, reward, resource, alliance and territory messages to the eco-log channel. Membership and rank messages stay in guild chat.
- Keep a permanent record of captured guild system messages.

## 0.3.9

- Keep guild rank badges when a chat mod reveals a player’s real name beside their nickname.

## 0.3.8

- Show guild actions in Discord as the player who made them, with their head and rank, including players using a nickname.
- Keep guild-wide actions, and actions whose player can't be confirmed, posted as the guild.
- Keep alliances, tributes and resource gifts posted as the guild unless the game shows which player made them.

## 0.3.7

- Ignore guild-style chat seen on other servers instead of sending it to Discord after you rejoin Wynncraft.
- Keep guild chat moving when an item preview is slow or fails to render.
- Drop unsent guild chat, including item previews still rendering, when you reconnect as a member of another guild.

## 0.3.6

- Forward scheduled guild tributes and resource gifts to Discord, including incoming gifts from allies.
- Show territory upgrade and bonus changes, bonus levels, and applied loadouts in Discord.

## 0.3.5

- Show guild joins, departures, kicks, invitations, rank changes, alliances, guild rewards and Everyone-tab guild bank transfers in Discord.

## 0.3.4

- Check for releases while Minecraft is running and show one Download prompt per version.
- Download updates only after you click Download or run `/odyssey update download`; install them when Minecraft closes.
- Keep checks quiet, with randomized intervals, failure backoff, and a saved opt-out.

## 0.3.3

- Connect automatically after linking your profile while playing Wynncraft, without needing `/odyssey reconnect`.
- Show one setup notice while waiting for the link, instead of a terminal connection error.

## 0.3.2

- Show online Alps and Vale members, their guilds and connected Odyssey versions with `/odyssey online` and Wayfinder's `/online`.

## 0.3.1

- Copy original Wynntils item codes from Discord previews.
- Match shared item embeds to their rarity colour, including crafted items.
- Fix release announcements failing to attach the mod.

## 0.3.0

- Show shared items in Discord with their in-game tooltip, colours, and item frame.
- Use Wynntils' decoder when installed; native hover items work without it.
- Keep chat readable when an item preview is unavailable.
- Keep linked guild ranks on item shares and WynnExtras bomb-share messages.

## 0.2.3

- Connect through any `*.wynncraft.com` address, including lobby and regional hosts.

## 0.2.2

- Keep settings and the release cache under `config/odyssey/`.
- Shorten the setup guide and move contributor details into separate documentation.
- Keep build defaults, tool versions, and development commands in Mise.

The old `config/odyssey.json` is no longer read. Set any custom preferences in
`config/odyssey/config.json`; automatic updates default to on.

## 0.2.1

- Fix a startup crash when Odyssey and Sequoia are installed together.
- Enable automatic updates by default; save explicit opt-outs across restarts.
- Build once in CI while retaining updater checks on Windows, Linux, and macOS.

If Odyssey cannot start, close Minecraft and replace its JAR manually.

## 0.2.0

- Java 21 support, matching Minecraft 1.21.11's usual launcher runtime.
- Background update checks with the usual Odyssey chat styling.
- Signed updates that install after Minecraft closes on Windows, Linux, and macOS.
- Opt-in automatic updates and a retained copy of the previous version.
- Update commands in the setup guide.
- Wayfinder release announcements with the changelog first, a version download link, and just the JAR attached.

Replace your old Odyssey JAR once to gain the updater. Automatic installation is
off by default; enable it with `/odyssey update auto on`.

## 0.1.0

After four months gathering dust in a closet, Odyssey is finally here.
A jolly little bridge for Alps and Vale, delivered with tremendous punctuality.

### Added

- Wynncraft guild chat bridged to Odyssey Discord.
- Automatic connection for linked Minecraft profiles.
- Discord messages in game, with guild rank styling.
- `/odyssey status` and `/odyssey reconnect` commands.

### Get started

Install `odyssey-mod.jar` and the dependencies below, link your Minecraft
profile with the Odyssey bot's `/link` command in Discord, then join Wynncraft.

The [setup guide](https://github.com/alps-vale/odyssey-mod/blob/main/README.md)
covers installation, commands, configuration, and manual updates.

Runtime: Minecraft 1.21.11, Java 25+, Fabric Loader 0.19.3+, Fabric API
0.141.6+1.21.11, and Fabric Language Kotlin 1.13.13+kotlin.2.4.10.

Updates are manual in this release.
