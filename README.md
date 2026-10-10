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

## Commands

In Minecraft:

- `/odyssey status`: show the connection and linked identity.
- `/odyssey reconnect`: reconnect after linking or fixing a connection problem.
- `/odyssey update`: show update status.
- `/odyssey update check`: check for a new release.
- `/odyssey update install`: download the available update for installation when Minecraft closes.
- `/odyssey update auto on` or `off`: enable or disable automatic updates.

If the bridge stays disconnected, check your linked profile and guild role,
then run `/odyssey reconnect`.

## Shared items

Items shared in guild chat appear in Discord as embeds with the in-game tooltip.
Wynntils shares need Wynntils installed on an observing client; native hover items
are also supported. If a preview cannot be rendered, the message still arrives
with a readable item label. Only the tooltip is rendered—never your screen.

## Updates

Automatic updates are on by default. Odyssey checks for stable releases at most
once a day and installs updates when Minecraft closes. Use `/odyssey update auto off`
to opt out; the setting is saved. You can still check and install updates manually.

Updates work on Windows, Linux, and macOS. Odyssey verifies the release signature,
JAR, and required dependencies before replacing the installed mod.

If Minecraft cannot start, close it and replace the Odyssey JAR manually.
For a failed update, use `/odyssey update install` to retry. To roll back, close
Minecraft and copy `mods/.odyssey-update/previous.jar` over the installed Odyssey JAR.
Automatic updates pause after a rollback; `/odyssey update auto on` resumes them.

## Settings

Settings live in `config/odyssey/config.json`. `autoConnect`, `bridgeVisible`,
`discordRankOverrides`, and `autoUpdate` default to `true`.
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
