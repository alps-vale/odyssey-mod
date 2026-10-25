# Odyssey

A Fabric mod that connects Alps and Vale guild chat on Wynncraft with Discord.

## Install

You need Minecraft 1.21.11, Java 21+, Fabric Loader 0.19.3+, Fabric API 0.141.6+1.21.11, and Fabric Language Kotlin 1.13.13+kotlin.2.4.10.

1. Download [odyssey-mod.jar](https://github.com/alps-vale/odyssey-mod/releases/latest/download/odyssey-mod.jar).
2. Put it and the required Fabric mods in your instance's `mods` folder. Keep only one Odyssey JAR installed.
3. Link your Minecraft profile with Wayfinder's `/link` command in Discord. You need an eligible Alps or Vale guild role.
4. Join Wynncraft through any `*.wynncraft.com` address, like `play.wynncraft.com` or `lobby.wynncraft.com`.

Odyssey connects on its own after that, and you just send messages through normal guild chat. If you link while already playing, Odyssey notices and connects without a relog.

## Commands

In Minecraft:

- `/odyssey status` shows the connection and your linked identity.
- `/odyssey online [page]` shows online Alps and Vale members, their guilds, and connected Odyssey versions.
- `/odyssey reconnect` retries the connection immediately.
- `/odyssey update` shows update status. `/odyssey update check` checks for a new release, and `/odyssey update download` downloads it for installation when Minecraft closes.
- `/odyssey update checks on` or `off` turns automatic update checks on or off.

If the bridge stays disconnected, check your linked profile and guild role, then run `/odyssey reconnect`.

Wayfinder also has a `/online` command in Discord that shows the same report privately. Wynncraft status refreshes every two minutes; Odyssey connections are live. So "not connected" doesn't mean someone has no mod installed.

## Shared items

Items shared in guild chat show up in Discord as embeds with the in-game tooltip. Native hover items work on their own. For a Wynntils share, someone receiving the in-game message needs Wynntils installed to provide a preview, and new-stat support follows their Wynntils version. If a preview can't be rendered, the message still arrives with a readable item label. Only the tooltip gets captured and rendered, never your screen.

## Guild events

These events also appear in Discord:

- Guild joins, departures, kicks and invitations, and rank changes
- Alliances, guild rewards, scheduled tributes, and resource gifts
- Deposits and withdrawals in the guild bank's Everyone tab (High Ranked transfers are not relayed)
- Territory upgrade, bonus and loadout changes

When the player can be confirmed, including through a nickname, the action posts as that player with their rank and head. Otherwise it posts as `[Alps] Guild` or `[Vale] Guild`. Alliances, tributes and resource gifts stay with the guild unless the game shows which player made them. Names appear as shown in game, including nicknames. Events aren't repeated in game chat, and Wynntils isn't required for any of this.

## Updates

Odyssey checks for stable releases at startup and about every 30 minutes while Minecraft is running. A new version gets one chat prompt. Click **Download** or run `/odyssey update download`; no update JAR is downloaded or installed without your approval. The update installs after Minecraft closes.

Use `/odyssey update checks off` to disable automatic checks. The setting is saved, and manual checks and downloads still work.

Checks fetch only public release metadata, without account identifiers, and back off if the release host is unavailable. Updates work on Windows, Linux, and macOS, and Odyssey verifies the release signature, JAR, and required dependencies before replacing the installed mod.

If an update fails, run `/odyssey update download` to retry. To roll back, close Minecraft and copy `mods/.odyssey-update/previous.jar` over the installed Odyssey JAR. If Minecraft can't start at all, close it and replace the Odyssey JAR manually.

## Settings

Settings live in `config/odyssey/config.json`. Close Minecraft before editing the file. The four keys are `autoConnect`, `bridgeVisible`, `discordRankOverrides`, and `checkUpdates`, all defaulting to `true`.

Setting `discordRankOverrides` to `false` keeps native Wynncraft rank and name styling in guild chat instead of replacing it with Discord ranks. Bridge messages between the guilds and in Discord stay as they are. The `updates/` subfolder is the release cache.

## Development

Install [Mise](https://mise.jdx.dev/), then run:

```sh
mise install --locked java python actionlint zizmor
mise run ci
```

`mise run build` creates `build/libs/odyssey-mod.jar`. See [development and releases](docs/development.md) for CI and publishing details, and the [changelog](CHANGELOG.md) for release notes.

## License

[MIT](LICENSE).
