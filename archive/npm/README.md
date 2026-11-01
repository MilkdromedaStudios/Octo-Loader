# Octo Launcher

**One-click mods for Minecraft, from any loader and any version, with world backups that keep your builds safe.**

```bash
npm install -g octo-launcher
octo-launcher
```

The first run downloads Octo Launcher from the [latest release](https://github.com/MilkdromedaStudios/Octo-Loader/releases/latest) and checks it against the release's SHA-256 checksums. If you don't have Java 17 or newer, it also downloads Eclipse Temurin 21, checked against Adoptium's own checksum. Everything is kept in `~/.octo-launcher`, and Octo Launcher keeps itself up to date.

- **Mods**: search Modrinth and install Fabric, Quilt, Forge and NeoForge mods, with everything they need, in one click. Mods built for other Minecraft versions work too: Octo Loader rebuilds them.
- **Worlds**: back up and restore any world. If a mod goes missing, Octo backs the world up and keeps the chunks that use it exactly as they were, so putting the mod back brings everything back.
- **Play**: one Launch button. Signing in and starting the game stay with the official Minecraft Launcher and your Microsoft account.

```bash
octo-launcher                    # open the app
octo-launcher help               # the command line: play, versions, worlds, backup, restore, update
octo-launcher --update           # look for a newer Octo Launcher now
octo-launcher --where            # where everything is kept
```

Behind a proxy? `HTTPS_PROXY` and `NO_PROXY` are used. On Windows you can also use the installer, `OctoLauncher-Setup.exe`, from the [releases](https://github.com/MilkdromedaStudios/Octo-Loader/releases/latest).

Apache-2.0. Not affiliated with Mojang or Microsoft.
