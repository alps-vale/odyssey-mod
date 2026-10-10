# Changelog

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
