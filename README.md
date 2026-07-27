# Octo Loader

One Minecraft mod loader that runs Fabric, Quilt, Forge and NeoForge mods **at the
same time, in the same instance** — and that will also load mods built for a
Minecraft version other than the one you are running, including mods nothing has
been able to load for a decade.

```
$ java -jar octo-loader.jar scan --modsDir mods --mcVersion 1.20.1

MOD                          VERSION        FORMAT       ERA          PLAN
--------------------------------------------------------------------------------
BuildCraft|Core              6.0.10         Forge (leg.. fml          fml -> modern: apply 95 API rules, stand in for anything still missing
IC2                          2.2.827        Forge (leg.. searge       searge -> modern: apply 95 API rules, stand in for anything still missing
voxelmap                     1.9.28         LiteLoader   searge       searge -> modern: apply 95 API rules, stand in for anything still missing
jei                          15.2.0         Forge        modern       matches this Minecraft - no translation needed
quilted                      2.0.0          Quilt        modern       matches this Minecraft - no translation needed
shiny                        1.4.2          Fabric       modern       matches this Minecraft - no translation needed
```

## Install and use Octo Loader

### Requirements

- Minecraft: Java Edition, installed and started at least once.
- Java 17 or newer. The official launcher normally selects a suitable Java
  runtime automatically; command-line and server installations must provide it.
- A backup of important worlds before trying mods from another Minecraft
  version. Octo can translate binary interfaces, but it cannot guarantee that
  an old mod's gameplay or save-data behavior still works.

### Install a client

Download `installer.jar` from the
[latest release](https://github.com/MilkdromedaStudios/Octo-Loader/releases/latest)
and double-click it. In the installer:

1. Choose **Client**.
2. Select the Minecraft directory. On a standard Windows installation this is
   `%APPDATA%\.minecraft`; custom launcher instances may use a different folder.
3. Select an installed vanilla Minecraft version and press **Install**. If the
   version is absent, start that version once in the Minecraft launcher first.
4. Open or restart the Minecraft launcher, select the new **Octo Loader**
   installation, and launch it.

The same operations are available from a terminal:

```bash
java -jar installer.jar                                   # window
java -jar installer.jar client --mcVersion 1.20.1         # client, no window
java -jar installer.jar uninstall --mcVersion 1.20.1      # removes everything it wrote
```

The installer needs no network access — the loader is inside it. It adds one jar
under `libraries/` and one profile under `versions/`, and touches nothing else.
It also creates the `mods` directory if it does not exist.

### Install mods

1. Close Minecraft before changing the mod set.
2. Download the mod's `.jar`, `.zip`, or `.litemod` file from a source you trust.
   Do not extract the archive.
3. Put the file directly in `<gameDir>/mods`. For the standard Windows client,
   that is `%APPDATA%\.minecraft\mods`. A launcher profile with a custom game
   directory uses that instance's `mods` folder instead.
4. Keep Fabric, Quilt, Forge, NeoForge, legacy Forge, and LiteLoader mods together
   in that one folder. Do **not** install another loader profile on top of Octo.
5. Start the **Octo Loader** installation. The log names every discovered and
   loaded mod; the complete log is `<gameDir>/.octo/logs/octo-loader.log`.

Octo relaxes a mod's declared Minecraft-version constraint by default, but
dependencies between mods still matter. Install any library mods listed on the
mod's download page. Add mods a few at a time when diagnosing a failure, and
check that each mod supports the client or server side on which it is installed.

Resource packs belong in `<gameDir>/resourcepacks`, not `mods`. A
`pack.mcmeta` error is produced by an invalid or outdated resource pack and does
not mean that Octo failed to find the mods directory. Sodium workaround and
missing Vulkan-extension messages describe the graphics driver/GPU; update the
graphics driver or disable the feature/mod that requires Vulkan if Minecraft
does not continue launching.

### Install a server

Create an empty server directory, place the vanilla server jar there, accept
Minecraft's EULA as required, and run:

```bash
java -jar installer.jar server --dir /srv/minecraft
```

The installer creates server start scripts and `/srv/minecraft/mods`. Put
server-compatible mod archives in that folder, then use `start-octo-server.sh`
on Linux/macOS or `start-octo-server.bat` on Windows. Clients must also install
any mods that the mod author marks as required on both sides.

## What it does that the others do not

The four loaders each refuse two things: mods from another ecosystem, and mods
from another Minecraft version. Octo does neither.

### One runtime, four ecosystems

There is one mod list, one lifecycle, and one object share. A Forge mod calling
`ModList.get().isLoaded("sodium")` gets a true answer about a Fabric mod. A
Fabric mod calling `FabricLoader.getInstance().getObjectShare()` reads values a
NeoForge mod published. Mods do not need to know Octo exists.

| Format | File | Era |
|---|---|---|
| Fabric | `fabric.mod.json` | 1.14+ |
| Quilt | `quilt.mod.json` | 1.18+ |
| Forge | `META-INF/mods.toml` | 1.13+ |
| NeoForge | `META-INF/neoforge.mods.toml` | 1.20.5+ |
| Forge (legacy) | `mcmod.info` | 1.2.5 – 1.12.2 |
| LiteLoader | `litemod.json` | 1.5.2 – 1.12.2 |
| Bare jar | *(none — recovered from bytecode)* | any |

Jar-in-jar works for both conventions: Fabric/Quilt `jars`, and Forge's
`META-INF/jarjar/`.

### One mixin environment, and one set of access rules

Almost every current mod changes the game through [Mixin](https://github.com/SpongePowered/Mixin)
and through an access file that opens up the parts of the game it was compiled
against. Both are ecosystem-specific in name only, so Octo runs one of each:

- **Mixins.** Fabric's `mixins` array, Quilt's `mixin`, Forge's `[[mixins]]` and
  the `MixinConfigs` manifest attribute are all read into a single mixin
  environment, so a Fabric mod and a Forge mod can mix into the same class.
  MixinExtras is bundled and initialised, as Fabric Loader does, because current
  mods assume the loader provides it.
- **Access.** Fabric's `.accesswidener` and Forge's `accesstransformer.cfg` are
  parsed into one table and applied to the game as it loads. A class widened by a
  Forge mod is open to a Fabric one.

A mod whose mixins cannot be applied — one needing a newer Java than the JVM is
running, say — loses its mixins and says so. It does not cost every other mod
theirs, and it does not stop the game starting.

### Mods from any version — the time capsule

This is the part no other loader has. A mod built for 1.7.10 was compiled against
classes that no longer exist, so every other loader stops there. What actually
stands between that mod and a modern game is a sequence of mechanical changes,
and Octo applies them in order:

1. **Rename.** Mapping tables are composed into a route from the mod's namespace
   to the running game's — MCP to obfuscated to intermediary to Mojang's names,
   however many hops it takes — and every reference in the mod is rewritten.
   `func_71410_x()` becomes `getInstance()`. SRG, TSRG, TSRG2 and Tiny v2 files
   are all read; drop them in `<gameDir>/.octo/mappings/` named
   `<from>-to-<to>.<ext>` and any route through them is found automatically.
2. **Migrate.** Rules cover what renaming cannot express: a method that moved to
   another class, an instance method that became static, a field that turned into
   a getter. These ship with the loader and can be extended per-instance — see
   [`loader/src/main/resources/octo/api/README.md`](loader/src/main/resources/octo/api/README.md).
3. **Stand in.** For classes that are simply gone, Octo reads what the mod needs
   from them — which methods, with which signatures, used how — and generates a
   class of exactly that shape at load time. The mod links and runs; calls into
   the dead API return a default rather than killing the mod at class-load time.

Every change is recorded. `octo scan` prints what would be done to each mod
before you launch, and the same notes are attached to each mod at runtime, so
when a mod misbehaves it is clear what was altered underneath it.

**What this is not.** Translation is mechanical, and mechanical translation
cannot invent behaviour. A 1.7.10 mod that registers blocks through a registry
that no longer exists will load, run its initialiser, and register nothing.
Octo's claim is that the mod runs and that what did not survive is reported —
not that a decade-old mod becomes a working modern mod. Mods that only touch API
that still exists in some form fare best; deep world-generation and rendering
mods fare worst.

### Version checks are advisory

Nearly every mod ever published declares a narrow game version, and enforcing
that is what makes an old mod "incompatible" even when nothing it calls has
changed. Octo treats an unsatisfied *game* version bound as a warning and hands
the mod to the translation layer. Bounds *between mods* are still enforced —
those describe APIs the loader cannot fix. `--strict` restores the usual
behaviour.

## Command line

```
octo launch [options] [-- game arguments]   start Minecraft with every mod in the folder
octo scan   [options]                       report what is there and what would be done
octo version

  --gameDir <path>      game directory (default: current directory)
  --modsDir <path>      mods folder, repeatable
  --gameJar <path>      Minecraft jar, repeatable
  --mcVersion <ver>     Minecraft version, when it cannot be read from the jar
  --side client|server  which side to load for
  --no-translate        do not translate mods built for older versions
  --no-stubs            do not stand in for classes that no longer exist
  --strict              refuse to start if any mod cannot be loaded
  --debug               verbose logging
```

When launched from a launcher profile the entrypoint is
`studios.milkdromeda.octo.launch.OctoMain`, which reads the same switches from
system properties (`-Docto.strict=true`, `-Docto.noTranslate=true`,
`-Docto.noStubs=true`, `-Docto.debug=true`) so the launcher's own argument list
is left alone.

## How the four loaders are merged

The upstream sources are vendored in [`upstream/`](upstream/) and the merge
happens at the layer that matters: **mods link against concrete classes**, not
against "a loader". A Fabric mod imports `net.fabricmc.api.ModInitializer`; a
NeoForge mod carries `net.neoforged.fml.common.Mod`. So Octo compiles those
upstream sources straight into its own jar and implements the half behind them
itself.

| Ecosystem | What is vendored and compiled in | What Octo implements |
|---|---|---|
| Fabric | `net.fabricmc.api.*`, `net.fabricmc.loader.api.*`, the version parser — the whole mod-facing API, verbatim | `FabricLoaderImpl`, `ModContainer`, `ModMetadata`, `ObjectShare`, `MappingResolver`, the language adapter |
| Quilt | `LanguageAdapterException`, `VersionFormatException` | `QuiltLoader`, `ModContainer`, `ModMetadata`, the QSL entrypoint interfaces |
| NeoForge | `net.neoforged.fml.common.Mod`, verbatim | `Dist`, the event bus, the lifecycle events, `ModList`, `ModLoadingContext` |
| Forge | *(reference only)* | `@Mod` covering both the 1.7 and 1.13 shapes, the event bus, both generations of lifecycle event, `ModList`, `FMLJavaModLoadingContext` |

What is deliberately *not* merged is each loader's bootstrap half — Knot,
ModLauncher, SecureJarHandler, Quilt's plugin system. Those are mutually
exclusive by construction: each one expects to own the class loader and the
game's entrypoint, and two of them in one JVM is not a merge conflict but a
contradiction. Octo replaces all four with a single transforming class loader
that every ecosystem's API sits on top of. That is the only arrangement in which
they can share a process — and it is what makes the cross-ecosystem mod list
possible rather than being a compromise against it.

Quilt's and Forge's API surfaces are reimplementations rather than copies for a
practical reason: Quilt's API is entangled with its plugin and virtual-filesystem
layers, and current Forge's `@Mod` reaches into `BusGroup` and `Bindings`.
Pulling either in would drag in the bootstrap half that has just been replaced.
The signatures mods compile against are unchanged.

## Development

### Prerequisites and checkout

Development requires Git and a JDK 17 or newer; CI builds with JDK 21 while
emitting Java 17 bytecode. The checked-in Gradle wrapper downloads the expected
Gradle version, so a system Gradle installation is not required.

```bash
git clone https://github.com/MilkdromedaStudios/Octo-Loader.git
cd Octo-Loader
./gradlew --version
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

### Build, test, and package

```bash
./gradlew build                      # compile and run the complete test suite
./gradlew test                       # run tests without packaging everything
./gradlew :loader:test               # loader tests only
./gradlew :installer:test            # installer tests only
./gradlew :installer:installerJar    # installer/build/installer/installer.jar
./gradlew :loader:jar                # loader/build/libs/octo-loader-1.0.0.jar
./gradlew clean                      # remove generated build output
```

Build artifacts are written below `loader/build/` and `installer/build/`; these
directories are generated and should not be committed. Dependency downloads
come from Maven Central and FabricMC's Maven repository.

To inspect a local mod folder without starting Minecraft, build the loader and
run its scan command:

```bash
java -jar loader/build/libs/octo-loader-1.0.0.jar scan \
  --gameDir /path/to/instance \
  --modsDir /path/to/instance/mods \
  --mcVersion 1.20.1
```

Use `--debug` for verbose console diagnostics. Runtime trace output is also
written beneath the selected game directory in `.octo/logs/octo-loader.log`.

### Source organization

The root project has two Gradle subprojects. `loader` builds the self-contained
runtime and command-line scanner; `installer` embeds that loader jar and builds
the GUI/client/server installer. Mod-facing portions of Fabric Loader, Quilt
Loader, Forge, and NeoForge are vendored under `upstream/` and imported by the
loader build. Do not edit generated sources under `build/generated`; change
Octo's sources or refresh the corresponding vendored upstream instead.

### Tests and validation

The vendored loader APIs are checked for updates every day. The
[`sync-upstreams` workflow](.github/workflows/sync-upstreams.yml) refreshes all
four source trees, records their exact commits, runs the complete combined-loader
test suite, and opens a pull request when anything changed. Updates therefore
remain automatic without publishing an untested upstream change directly to
users. Maintainers can run the same refresh locally with
`python3 scripts/sync-upstreams.py`.

That sync command requires Python 3, Git, and network access. After a sync,
review the changes in `upstream/` and `THIRD-PARTY-NOTICES.md`, then run
`./gradlew build --stacktrace`. Do not commit `.gradle/` or any `build/`
directory.

The tests are not smoke tests. The end-to-end suite compiles six real mods — one
per ecosystem, plus a LiteLoader mod and one written against a 2014 API that is
then never provided again — packs them into real jars, and boots the actual
loader against a synthetic game jar. It asserts that all six run, that the Forge
mod can see the Fabric mod, and that the 1.7.10 mod's `Minecraft.getMinecraft()`
call reaches today's `getInstance()` and returns the running game.

The mixin suite does the same for the other half: it compiles a mod against a
*widened* view of the game — private members public, final classes open, exactly
as a mod is really built — hands the loader the restricted original, and then
asserts that the mod's `@Inject` changed the game's behaviour, that its
`@Invoker` reached a private method, that its access widener made another one
callable, and that a class the game declares `final` was subclassed.

Before submitting a change, run at least:

```bash
./gradlew build --stacktrace
git diff --check
git status --short
```

The `build.yml` workflow repeats the build, packages the installer, scans six
sample mod formats, and publishes release artifacts from `main`.

## Layout

```
loader/                     the loader runtime
  mod/                      the canonical mod model and the six metadata parsers
  discovery/                finding mods, and reading entrypoints out of bytecode
  resolve/                  dependency resolution and load order
  compat/                   eras, mappings, API rules — the time capsule
  transform/                remapping, API migration, generated stand-in classes
  access/                   access wideners and access transformers, merged
  mixin/                    the mixin service and the one mixin environment
  bridge/                   per-ecosystem construction and lifecycle
  launch/                   the class loader and the launcher
  compat/<ecosystem>/       the implementation behind each upstream API
installer/                  installer.jar: client, server, GUI and CLI
upstream/                   vendored Fabric, Quilt, Forge and NeoForge sources
```

## Licences

Octo Loader's own code is Apache-2.0 (see [`LICENSE`](LICENSE)). The vendored
upstream sources stay under their own licences — Fabric Loader and Quilt Loader
are Apache-2.0, Forge and NeoForge's FancyModLoader are LGPL-2.1. Distributing a
binary that combines Apache-2.0 and LGPL-2.1 code is not something to do
casually; the specifics, including which files come from where and at which
commit, are in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md). If you plan to
redistribute this, read that file first.

Not affiliated with Mojang, FabricMC, QuiltMC, Forge or NeoForged.
