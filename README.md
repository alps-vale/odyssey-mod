# Odyssey

A Fabric mod that connects Alps and Vale guild chat on Wynncraft with Discord.

## Install

Requires Minecraft 1.21.11, Java 21+, Fabric Loader 0.19.3+,
Fabric API 0.141.6+1.21.11, and Fabric Language Kotlin 1.13.13+kotlin.2.4.10.

1. Download [odyssey-mod.jar](https://github.com/alps-vale/odyssey-mod/releases/latest/download/odyssey-mod.jar).
2. Put it and the required Fabric mods in your instance's `mods` folder.
   Keep only one Odyssey JAR installed.
3. Link your Minecraft profile with Wayfinder's `/link` command in Discord.
   You need an eligible Alps or Vale guild role.
4. Join Wynncraft through any `*.wynncraft.com` address, such as `play.wynncraft.com`
   or `lobby.wynncraft.com`.
   Odyssey connects automatically; send messages through normal guild chat.
   You can finish linking while already playing; Odyssey checks again automatically.

## Commands

In Minecraft:

- `/odyssey status`: show the connection and linked identity.
- `/odyssey online [page]`: show online Alps and Vale members, their guilds, and connected Odyssey versions.
- `/odyssey reconnect`: retry the connection immediately.
- `/odyssey update`: show update status.
- `/odyssey update check`: check for a new release.
- `/odyssey update download`: download the available update for installation when Minecraft closes.
- `/odyssey update checks on` or `off`: enable or disable automatic update checks.

If the bridge stays disconnected, check your linked profile and guild role,
then run `/odyssey reconnect`.

In Discord, Wayfinder's `/online` command shows the same report privately.
Wynncraft status refreshes every two minutes; Odyssey connections are live.
"Not connected" does not mean someone has no mod installed.

## Shared items

Items shared in guild chat appear in Discord as embeds with the in-game tooltip.
Wynntils shares need Wynntils installed on an observing client; native hover items
are also supported without it. New-stat support follows that client's Wynntils
version. If a preview cannot be rendered, the message still arrives
with a readable item label. Only the tooltip is rendered—never your screen.

## Guild events

Guild joins, departures, kicks and invitations, rank changes, alliances, guild rewards,
deposits and withdrawals in the guild bank's Everyone tab, scheduled tributes, resource gifts,
and territory upgrade, bonus and loadout changes also appear in Discord,
posted as `[Alps] Guild` or `[Vale] Guild`. They are not repeated in game chat, and
Wynntils is not required. Names appear as shown in game, including nicknames.
High Ranked bank transfers are not relayed.

## Updates

Odyssey checks for stable releases at startup and about every 30 minutes while
Minecraft is running. A new version gets one chat prompt. Click **Download** or
run `/odyssey update download` to download it; no update JAR is downloaded or
installed without your approval. The update installs after Minecraft closes.

Use `/odyssey update checks off` to disable automatic checks; the setting is saved.
Manual checks and downloads still work. Checks fetch only public release metadata,
without account identifiers, and back off if the release host is unavailable.

Updates work on Windows, Linux, and macOS. Odyssey verifies the release signature,
JAR, and required dependencies before replacing the installed mod.

If Minecraft cannot start, close it and replace the Odyssey JAR manually.
For a failed update, use `/odyssey update download` to retry. To roll back, close
Minecraft and copy `mods/.odyssey-update/previous.jar` over the installed Odyssey JAR.

## Settings

Settings live in `config/odyssey/config.json`. `autoConnect`, `bridgeVisible`,
`discordRankOverrides`, and `checkUpdates` default to `true`.
Close Minecraft before editing the file. The `updates/` subfolder holds the release cache.

## Development

Install [Mise](https://mise.jdx.dev/), then run:

```sh
mise install --locked java python actionlint zizmor
mise run ci
```

`mise run build` creates `build/libs/odyssey-mod.jar`.
See [development and releases](docs/development.md) for CI and publishing details,
and the [changelog](CHANGELOG.md) for release notes.

## License

[MIT](LICENSE).
