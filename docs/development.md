# Development and releases

## Checks

- `mise run ci`: build, test, and lint the workflow.
- `mise run test`: run the unit tests.
- `mise run build`: create `build/libs/odyssey-mod.jar`.

These commands do not install or launch Minecraft.

### Item previews

`mise run test-item-render` launches an isolated Minecraft client, renders a
native hover-item fixture, and exits. It needs a display. The PNG and log are in
`build/item-smoke/`; your normal game instance is untouched.

To exercise Wynntils decoding too, put its Minecraft 1.21.11 JAR in
`build/item-smoke/mods/`. Put your cached Wynncraft resource pack in
`build/item-smoke/resourcepacks/wynncraft.zip` for the server's fonts and textures.
The test uses Wynntils' own encoder to create a Stratiformis fixture, then checks
Odyssey's decoding and rendering path. These files are not bundled in releases.

## Repository configuration

`mise.toml` owns tool versions, commands, and build defaults. Gradle/Loom owns
dependencies, compilation, and remapping; Actions owns runners and publishing.
For other Gradle commands, use `mise exec -- ./gradlew TASK` (`gradlew.bat` on Windows).

Override local settings in an ignored `mise.local.toml`, for example:

```toml
[env]
ODYSSEY_PRISM_INSTANCE = "my-instance"
PRISM_ROOT = "/path/to/PrismLauncher"
```

`ODYSSEY_BACKEND_URL` and `ODYSSEY_VERSION` can also be overridden there or in the
environment. `ODYSSEY_DEVELOPMENT` defaults to `false`; set it to `true` for a development
build. Release builds take their version from the tag. The `install`, `menu`,
and `launch` tasks change the configured PrismLauncher instance; the checks do not.

## CI

CI builds the mod and runs the full test suite on Linux. Windows and macOS
(Apple Silicon and Intel) run compiled updater checks from that build.
Tools and Gradle state are cached; only `main` writes shared caches.
Superseded PR checks are cancelled. Release jobs publish the tested Linux JAR
without rebuilding it, after every platform check passes.

## Publishing

Update `CHANGELOG.md`, then push an annotated `vMAJOR.MINOR.PATCH` tag from `main`,
using that version's changelog as the tag message. CI creates the GitHub release
and posts the changelog, version download link, and runnable JAR to Discord as Wayfinder.

Repository Actions secrets:

- `DISCORD_RELEASE_WEBHOOK_URL`: webhook for the release channel.
- `ODYSSEY_UPDATE_SIGNING_KEY`: base64 PKCS#8 Ed25519 private key matching
  `src/main/resources/odyssey-update.pub`.

Stable releases include `update.manifest` and `update.manifest.sig` for the updater.
The private key is used only in the signing step. Keep the key stable; changing the
pinned public key needs a manual client update or a separate key migration.
