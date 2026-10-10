# Odyssey

A Fabric mod that connects Alps and Vale guild chat on Wynncraft with Discord.

## Setup

Use Minecraft 1.21.11, Java 21 or newer, and Fabric Loader 0.19.3 or newer.

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
- `/odyssey update`: show update status.
- `/odyssey update check`: check for a new release.
- `/odyssey update install`: install the available update when Minecraft closes.
- `/odyssey update auto on` or `off`: enable or disable automatic updates.
- `/link` in Discord: link your Minecraft profile with the Odyssey bot.

Send messages through normal Wynncraft guild chat; no separate mod command is needed.
If the bridge stays disconnected, check `/odyssey status`, your linked profile,
and your guild role, then run `/odyssey reconnect`.

The mod creates `config/odyssey.json` in your instance. `autoConnect`,
`bridgeVisible`, and `discordRankOverrides` default to `true`.
Restart Minecraft after changing these settings.

### Updates

Odyssey checks for stable releases in the background, at most once a day.
Click **Update** in the chat notice to download an update; it installs after
Minecraft closes. Automatic installation is off by default. Enable it with
`/odyssey update auto on` if you want future updates installed without asking.

The updater works on Windows, Linux, and macOS. It checks the release signature,
the JAR, and your instance's dependencies before changing anything. It only
replaces the installed Odyssey JAR, and keeps the previous copy in
`mods/.odyssey-update/previous.jar`.

If an update fails, the installed JAR stays in place. Try
`/odyssey update install` again, or close Minecraft and replace the JAR manually.
To roll back, close Minecraft and copy `previous.jar` over your Odyssey JAR.
Automatic updates pause after a rollback; `/odyssey update auto on` resumes them.
Keep only one Odyssey JAR in `mods`. The updater cannot fix a Minecraft startup
crash before Odyssey loads.

**Version 0.1.0 needs one manual update** to gain the updater.

## Development

Install [Mise](https://mise.jdx.dev/), then run:

```sh
mise install --locked java python actionlint zizmor
mise run ci
```

`mise run build` creates `build/libs/odyssey-mod.jar`.
CI checks the build and installer on Linux, Windows, and macOS (Apple Silicon
and Intel). It caches tools and Gradle state per platform, cancels superseded
checks, and runs workflow/release-helper checks once on Linux. Only `main`
updates shared caches; PRs and releases read them. These commands do not install
or launch Minecraft.

## Releases

Push a tag such as `v0.1.0` from `main`. After CI passes, the workflow creates a
GitHub release and posts its changelog and runnable JAR to Discord as Wayfinder.
The announcement includes a direct download link for that version.
Set `DISCORD_RELEASE_WEBHOOK_URL` in the repository's Actions secrets.
Stable releases also include `update.manifest` and its Ed25519 signature.
Set `ODYSSEY_UPDATE_SIGNING_KEY` to the base64 PKCS#8 signing key; the matching
public key is pinned in `src/main/resources/odyssey-update.pub`. The private key
is used only in the release-signing step, never in builds or clients.
Keep that key stable: changing the pinned public key requires a manual client
update or an explicit key migration.

## License

[MIT](LICENSE).
