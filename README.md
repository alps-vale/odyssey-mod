# Odyssey

A Fabric mod connecting Alps and Vale guild chat on Wynncraft with Discord.

## Features

- Cross-guild and Discord chat
- Item tooltip previews in Discord
- Guild activity messages
- See who's online

## Building with Mise

Install [Mise](https://mise.jdx.dev/), then:

```sh
mise install --locked java python actionlint zizmor
mise run ci
mise run build
```

`ci` runs the build, tests and workflow linting. `build` compiles the mod into `build/libs/odyssey-mod.jar`; it does not install or launch Minecraft.

Once the mod is running in game, `/odyssey status` shows the current connection state.

## Docs

- [Player guide](docs/usage.md): installation, commands and settings
- [Development and releases](docs/development.md)
- [Changelog](CHANGELOG.md)

## License

[MIT](LICENSE)
