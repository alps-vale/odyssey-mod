# Odyssey

A Fabric mod that connects Alps and Vale guild chat on Wynncraft with Discord.

## Setup

Use Minecraft 1.21.11, Java 25 or newer, and Fabric Loader 0.19.3 or newer.

1. Download `odyssey-mod.jar` from the [latest release](https://github.com/alps-vale/odyssey-mod/releases/latest)
   or the Odyssey release announcement in Discord.
2. Put it in your Minecraft instance's `mods` folder with Fabric API
   0.141.6+1.21.11 and Fabric Language Kotlin 1.13.13+kotlin.2.4.10.
3. Link your Minecraft profile using the Odyssey bot's `/link` command in
   Discord. You also need an eligible Alps or Vale guild role.
4. Launch the Fabric instance and join Wynncraft. Odyssey connects automatically.

### Commands

- `/odyssey status` in Minecraft: show the bridge connection and linked identity.
- `/odyssey reconnect` in Minecraft: reconnect after linking or fixing a connection problem.
- `/link` in Discord: link your Minecraft profile with the Odyssey bot.

Send messages through normal Wynncraft guild chat; no separate mod command is needed.
If the bridge stays disconnected, check `/odyssey status`, your linked profile,
and your guild role, then run `/odyssey reconnect`.

The mod creates `config/odyssey.json` in your instance. `autoConnect`,
`bridgeVisible`, and `discordRankOverrides` default to `true`.
Restart Minecraft after changing these settings.

Updates are manual for now: close Minecraft, replace the old Odyssey JAR with
the new one, and reopen the instance. Keep only one Odyssey JAR in `mods`.

## Development

Install [Mise](https://mise.jdx.dev/), then run:

```sh
mise install --locked java python actionlint zizmor
mise run ci
```

`mise run build` creates `build/libs/odyssey-mod.jar`.
CI checks the build on Linux and Windows. These commands do not install or
launch Minecraft.

## Releases

Push a tag such as `v0.1.0` from `main`. After CI passes, the workflow creates a
GitHub release and posts its changelog and runnable JAR to Discord.
Set `DISCORD_RELEASE_WEBHOOK_URL` in the repository's Actions secrets.

## License

[MIT](LICENSE).
