<p align="center">
  <img src="docs/logo.svg" width="168" alt="Octo Loader logo">
</p>

<h1 align="center">Octo Loader</h1>

<p align="center">
  <b>One loader for every Minecraft mod.</b><br>
  Fabric, Quilt, Forge and NeoForge mods together in one game, including mods built for other Minecraft versions.
</p>

<p align="center">
  <a href="https://github.com/MilkdromedaStudios/Octo-Loader/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/MilkdromedaStudios/Octo-Loader?color=7c3aed&label=release"></a>
  <img alt="Minecraft 26.3" src="https://img.shields.io/badge/Minecraft-26.3-6610e8">
  <img alt="Loaders" src="https://img.shields.io/badge/loads-Fabric%20%C2%B7%20Quilt%20%C2%B7%20Forge%20%C2%B7%20NeoForge-00b2ff">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-9b1cff">
  <a href="LICENSE"><img alt="Licence" src="https://img.shields.io/badge/licence-Apache--2.0-4b5563"></a>
</p>

<p align="center">
  <a href="#quick-start">Quick start</a> ·
  <a href="#installing-mods">Installing mods</a> ·
  <a href="#troubleshooting">Troubleshooting</a> ·
  <a href="#how-it-works">How it works</a> ·
  <a href="#building-from-source">Building from source</a>
</p>

---

> [!WARNING]
> **Back up your worlds before you play with mods made for another Minecraft version.**
> Octo rebuilds those mods so they run, but it cannot promise that what they save can be read back
> correctly. Try them in a new world first. [Why?](#a-warning-about-mods-from-other-versions)

## Why Octo?

|  |  |
|---|---|
| 🧩 **Mix every loader** | Fabric, Quilt, Forge, NeoForge, legacy Forge and LiteLoader mods go in **one** `mods` folder and run in **one** game. They can even see each other. |
| 🕰️ **Play mods from other versions** | A mod built for 1.21.1, 1.20.1 or 1.12.2 is unzipped, rewritten for the game you are running, re-jarred and loaded. Your original file is never touched. |
| 🎨 **Old mods still render** | Block entity renderers, block models and baked quads written for the old renderer are bridged to 26.x's renderer, so moving parts such as Create's shafts and cogwheels draw. |
| 🚀 **Fast after the first launch** | Rebuilt mods are cached. The next launch with the same mods loads them straight from the cache. |
| 🛟 **The game always starts** | A mod that cannot load does not stop you playing. Octo starts the game and shows you what is missing and why. |
| 🔍 **No guessing** | Every change made to a mod is listed in the log and the mod report, so you can always see what was adapted. |

## Quick start

**You need:** Minecraft: Java Edition, with the version you want to mod started at least once. Octo is built and tested against **Minecraft 26.3**. The official launcher provides the right Java automatically.

1. **Download** `installer.jar` from the [latest release](https://github.com/MilkdromedaStudios/Octo-Loader/releases/latest).
2. **Run it** (double-click), choose **Client**, pick your Minecraft folder and version, then press **Install**.
   On Windows the Minecraft folder is usually `%APPDATA%\.minecraft`.
3. **Add mods:** drop mod `.jar` files into the `mods` folder inside your Minecraft folder.
4. **Play:** open the Minecraft launcher, choose the **Octo Loader** installation and press **Play**.

That's it. The installer works offline (the loader is inside it), adds one library jar and one launcher profile, and touches nothing else.

<details>
<summary><b>Prefer the terminal?</b></summary>

```bash
java -jar installer.jar                                   # open the installer window
java -jar installer.jar client --mcVersion 26.3           # install for the client, no window
java -jar installer.jar server --dir /srv/minecraft       # set up a server
java -jar installer.jar uninstall --mcVersion 26.3        # remove everything the installer wrote
```

</details>

## Installing mods

1. **Close Minecraft** before you change your mods.
2. **Download** the mod's `.jar`, `.zip` or `.litemod` file from a source you trust. Don't unzip it.
3. **Drop it** straight into `<Minecraft folder>/mods`. A launcher installation with its own game directory uses that directory's `mods` folder instead.
4. **Mix freely.** Fabric, Quilt, Forge, NeoForge, legacy Forge and LiteLoader mods all go in the same folder. Don't install another loader on top of Octo.
5. **Start the Octo Loader installation.** The log names every mod found and loaded.

**Library mods still matter.** If a mod's download page says it needs another mod (Fabric API, Architectury, Cloth Config, Balm, …), install that one too. NeoForge and Forge mods need the loader's own API as well; Octo downloads it for you the first time it is needed.

**Mods from other versions just go in the same folder.** Octo notices that a mod was made for a different Minecraft version and rebuilds it on the first launch, which needs an internet connection once to fetch the name tables for that version. Afterwards the rebuilt copy is cached. You'll see a warning in the log and the report window whenever such a mod is loaded.

**Adding many mods?** Add a few at a time. If something goes wrong, you'll know which mod caused it.

### Tested on Minecraft 26.3

These sets are part of Octo's own test runs on a 26.3 server. Every mod listed starts up and runs. Mods built for an older version are rebuilt for 26.3 as described above.

| Set | Mods |
|---|---|
| **Create and add-ons** (NeoForge 1.21.1) | Create 6.0.10 with Flywheel and Ponder; Steam 'n' Rails, Create Connected, Copycats+, Create Deco, Create Crafts & Additions, Create Enchantment Industry, Create: Dragons Plus, Slice & Dice |
| **Popular NeoForge 1.21.1** | JEI, Jade, AppleSkin, Farmer's Delight, Supplementaries and Moonlight, Sophisticated Backpacks, Waystones and Balm, GeckoLib |
| **NeoForge 26.3** | JEI, AppleSkin, Architectury, Balm, Cloth Config, FerriteCore, Sodium |
| **Forge 26.3** | Collective, GeckoLib, Mouse Tweaks, No Chat Reports, TerraBlender |
| **Fabric** | Fabric API with AppleSkin, Cloth Config and more |
| **Forge 1.12.2** | AppleSkin |

On the 26.3 client, Create's world renders with its moving parts drawn by their own renderers.

"Runs" means the mod loads and its content is in the game. A rebuilt mod can still have features that do nothing on a version it was never written for. The [mod report](#the-game-always-starts) lists exactly what was adapted or skipped.

## Running a server

Put the vanilla `server.jar` in an empty folder, accept Minecraft's EULA, then run:

```bash
java -jar installer.jar server --dir /srv/minecraft
```

The installer creates `start-octo-server.sh` (Linux/macOS) and `start-octo-server.bat` (Windows) plus a `mods` folder. Put server-side mods in that folder and start the server with the script. Players need any mod that its author marks as required on both sides.

## Troubleshooting

<details>
<summary><b>The game started, but a mod is missing or doesn't work</b></summary>

Octo starts the game even when a mod can't load, and opens a report window listing every problem with its cause. The same report is saved to `<Minecraft folder>/.octo/logs/mod-report.txt`. The most common causes are a missing library mod, or a mod that only works on the other side (client vs. server).

</details>

<details>
<summary><b>Where are the logs?</b></summary>

| File | What's in it |
|---|---|
| `.octo/logs/octo-loader.log` | Everything Octo did, including debug detail and anything that happened before Minecraft's own logging started |
| `.octo/logs/mod-report.txt` | Which mods run, which needed changes, and which could not load |
| `logs/latest.log` | Minecraft's own log |
| `crash-reports/` | Minecraft's crash reports |

All paths are inside your Minecraft folder (or the installation's game directory).

</details>

<details>
<summary><b>Minecraft crashes or won't start</b></summary>

1. Read the crash window. It explains what the game was doing and what the error means in plain words.
2. Start once with **no mods** to rule mods out: in the launcher, go to *Installations → Octo Loader → Edit → More options* and add `-Docto.safeMode=true` to the JVM arguments.
3. Remove half your mods and try again to find the one causing the problem.
4. Still stuck? [Open an issue](https://github.com/MilkdromedaStudios/Octo-Loader/issues) and attach `octo-loader.log` and `mod-report.txt`.

</details>

<details>
<summary><b>I have no internet connection</b></summary>

Mods made for your exact Minecraft version need nothing from the network. A mod from another version needs the name tables for its version once, and NeoForge and Forge mods need their loader's API once. Both are cached in `.octo/cache/`. With `-Docto.offline=true`, Octo uses only what is already cached.

</details>

<details>
<summary><b>"pack.mcmeta" errors, or Vulkan / Sodium warnings</b></summary>

A `pack.mcmeta` error comes from an invalid or outdated resource pack in `resourcepacks/`, not from Octo. Messages about missing Vulkan extensions or Sodium workarounds describe your graphics driver: update it, or disable the feature or mod that needs Vulkan.

</details>

## Options

Add these to the launcher's JVM arguments (*Installations → Edit → More options*) or to your server's start script.

| Option | What it does |
|---|---|
| `-Docto.safeMode=true` | Start with no mods at all |
| `-Docto.offline=true` | Never use the network; use only what is cached |
| `-Docto.strict=true` | Stop at the first error instead of starting anyway (for builds and unattended servers) |
| `-Docto.strictVersions=true` | Refuse mods made for another Minecraft version instead of rebuilding them |
| `-Docto.noTranslate=true` | Don't rebuild mods made for other versions |
| `-Docto.noStubs=true` | Don't stand in for classes that no longer exist |
| `-Docto.noReportWindow=true` | Keep the report in the log and file only |
| `-Docto.reportHoldMinutes=<n>` | How long a crash window may keep a finished game open (default 30; `0` never) |
| `-Docto.noApiLibraries=true` | Don't download the NeoForge and Forge API jars |
| `-Docto.mappingsTarget=<version>` | The release whose names rebuilt mods are taken to (default `1.21.11`, the last with intermediary names) |
| `-Docto.noEarlyBootstrap=true` | Leave all of the game's start-up to Minecraft (diagnostics) |
| `-Docto.debug=true` | Verbose logging |

## Command line

The loader jar can also inspect a mods folder without starting the game:

```
$ java -jar octo-loader.jar scan --modsDir mods --mcVersion 26.3

MOD                          VERSION        FORMAT       ERA          PLAN
--------------------------------------------------------------------------------
BuildCraft|Core              6.0.10         Forge (leg.. fml          fml -> modern: apply 95 API rules, stand in for anything still missing
IC2                          2.2.827        Forge (leg.. searge       searge -> modern: apply 95 API rules, stand in for anything still missing
voxelmap                     1.9.28         LiteLoader   searge       searge -> modern: apply 95 API rules, stand in for anything still missing
jei                          15.2.0         Forge        modern       matches this Minecraft - no translation needed
quilted                      2.0.0          Quilt        modern       matches this Minecraft - no translation needed
shiny                        1.4.2          Fabric       modern       matches this Minecraft - no translation needed
```

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
  --strict              stop on the first mod that cannot be loaded
  --debug               verbose logging
```

From a launcher profile the entry point is `studios.milkdromeda.octo.launch.OctoMain`, which reads the same switches from the `-Docto.*` system properties above, so the launcher's own argument list is left alone.

## How it works

The four loaders each refuse two things: mods from another ecosystem, and mods from another Minecraft version. Octo does neither. The rest of this section explains how, for the curious and for mod authors.

### Supported mod formats

| Format | File | Era |
|---|---|---|
| Fabric | `fabric.mod.json` | 1.14+ |
| Quilt | `quilt.mod.json` | 1.18+ |
| Forge | `META-INF/mods.toml` | 1.13+ |
| NeoForge | `META-INF/neoforge.mods.toml` | 1.20.5+ |
| Forge (legacy) | `mcmod.info` | 1.2.5 – 1.12.2 |
| LiteLoader | `litemod.json` | 1.5.2 – 1.12.2 |
| Bare jar | *(none, recovered from bytecode)* | any |

Jar-in-jar works for both conventions: Fabric/Quilt `jars`, and Forge's `META-INF/jarjar/`. A jar-in-jar payload with no mod metadata goes on the class path as a library rather than being discarded, which is what Create's bundled Registrate needs.

### One runtime, four ecosystems

There is one mod list, one lifecycle and one object share. A Forge mod calling `ModList.get().isLoaded("sodium")` gets a true answer about a Fabric mod. A Fabric mod calling `FabricLoader.getInstance().getObjectShare()` reads values a NeoForge mod published. Mods do not need to know Octo exists.

Almost every current mod changes the game through [Mixin](https://github.com/SpongePowered/Mixin) and an access file. Both are ecosystem-specific in name only, so Octo runs one of each:

- **Mixins.** Fabric's `mixins` array, Quilt's `mixin`, Forge's `[[mixins]]` and the `MixinConfigs` manifest attribute are all read into a single mixin environment, so a Fabric mod and a Forge mod can mix into the same class. MixinExtras is bundled and initialised, as Fabric Loader does.
- **Access.** Fabric's `.accesswidener` and Forge's `accesstransformer.cfg` are parsed into one table and applied as the game loads. A class widened by a Forge mod is open to a Fabric one.

A mod whose mixins cannot be applied loses its mixins and says so. It does not cost every other mod theirs, and it does not stop the game starting. A mixin method that would make its target class fail the JVM's verifier is emptied rather than allowed to take the class down.

### Mods from other versions are rebuilt, not just tolerated

Since 26.1 Minecraft ships without obfuscation, under Mojang's own names, and every mod built for an earlier release speaks a different language from the game: Fabric's intermediary (`class_310`), Forge's SRG (`m_91087_`), or Mojang's names as they stood that year (`ResourceLocation`, which is `Identifier` now). Octo **translates those mods and loads the translation**:

1. **Unzip.** The mod jar is opened. The original in your mods folder is never modified.
2. **Reprogram.** Every class is rewritten into the names this game uses, along with everything that names game classes as text: mixin refmaps, access wideners, access transformers and reflection strings. The tables come from the people who published them (Mojang's deobfuscation maps, Fabric's intermediary and Forge's MCPConfig). Intermediary's numbers never change between versions, so they are the pivot: a 1.21.1 mod's `class_2960` is found in 1.21.11's tables, under today's name. Then the API rules for the eras crossed are applied.
3. **Jar again.** The result is packed into a new jar in `.octo/cache/rebuilt/`, and that jar is what loads.
4. **Cache.** The rebuilt jar is named after a hash of the original and of everything that went into it: Octo's version, the game version, the tables and the rules. Change any of those and it is rebuilt.

What does not survive translation in any form is handled at load time:

- **Missing classes are added back.** A class the game no longer has is restored from the release the mod was built for when it can be, and otherwise generated in exactly the shape the mod uses: its methods, constructors, fields and supertypes. Builder methods hand back the builder, factories hand back a usable object, an event bus field holds a working bus.
- **Missing members are bridged or bypassed.** A method that moved, was renamed, takes wider parameters, became static, or moved to a plural holder class (`RenderType.entitySolid` → `RenderTypes.entitySolid`) is reached the new way. A removed method whose old behaviour is a line or two of the new API is rebuilt from it. Anything else is skipped and a sensible default stands in: an empty list where a list was promised, the receiver for a builder setter, a real value where `null` would crash the next line. Members that other mods add through mixins count as present, so nothing a mod genuinely provides is bypassed.

Everything rebuilt, restored, bridged or bypassed is listed in the log and the mod report.

### Old rendering on the new renderer

26.x rewrote how blocks and block entities are drawn, which leaves every renderer written before it drawing nothing. Octo bridges the old shapes to the new pipeline:

- **Block entity renderers** that still draw through `render(blockEntity, partialTick, poseStack, buffers, light, overlay)` get 26.x's render-state methods. What the old method draws is recorded and replayed through the new renderer.
- **The old block renderer** (`BlockRenderDispatcher`, `ModelBlockRenderer`, `BlockModelShaper`) is added back, with each method answered from 26.x's block models.
- **Models a mod asks for by name** (NeoForge's and Forge's `ModelEvent.RegisterAdditional`, which Flywheel's partial models and many other mods use) are loaded and baked with the game's own models, then handed back through `BakingCompleted.getModels()` and `ModelManager.getModel`.
- **Baked quads** read and built the old way, as 32 ints of vertex data, are translated to and from 26.x's quad records, so mods that reshape or re-texture models keep working.
- **Vertex buffers** a mod builds itself get the vertex layout it was written for, and mixin accessors for render state 26.x removed answer a sensible default instead of crashing the frame.

### What the loaders' patched game did, done again

NeoForge and Forge ship a patched game. Much of what their mods rely on happens in those patches, and Octo runs the unpatched game, so it puts that behaviour back:

- **Game events.** Client, server, level and player ticks, server start and stop, command registration, levels loading and unloading, and players joining and leaving are all fired on NeoForge's game bus. Create's animations, contraptions, trains and commands run on these.
- **Capabilities.** Mods register what their blocks, entities and items offer (inventories, tanks, energy), so pipes, funnels and hoppers can find each other.
- **Networking.** Mods' own packets are written, read and handed to their handlers. Before this, the first packet a mod sent dropped the player from the world.
- **Removed APIs, provided.** NeoForge 26 replaced its item, fluid and energy handlers (`IItemHandler`, `ItemStackHandler`, `IFluidHandler`, `FluidTank`, `IEnergyStorage` and the rest) with a new transfer API. Octo carries working implementations of the old ones, saved in the same format, for the many mods built on them.

### A warning about mods from other versions

A translated mod is running against a game its author never tested. Most of what can go wrong is a feature that quietly does nothing, but some of it can reach your save: a block that saves under one name and loads under another, an entity whose data no longer means what it meant, a world generator writing chunks the game cannot read back. **Back up your worlds, and try such mods in a new world first.**

Octo says so every time such a mod loads: in a banner in the log, at the top of the report window and `mod-report.txt`, and in `octo scan`:

```
WARNING: 3 mod(s) were made for another Minecraft version and would run translated.
         They may misbehave or damage a world. Back up your worlds before playing.
```

<details>
<summary><b>Very old mods: the time capsule</b></summary>

A mod built for 1.7.10 was compiled against classes that no longer exist. What stands between that mod and a modern game is a sequence of mechanical changes, and Octo applies them in order:

1. **Rename.** Mapping tables are composed into a route from the mod's namespace to the running game's (MCP to obfuscated to intermediary to Mojang's names, however many hops it takes) and every reference in the mod is rewritten. `func_71410_x()` becomes `getInstance()`. SRG, TSRG, TSRG2 and Tiny v2 files are all read; drop your own in `<gameDir>/.octo/mappings/` named `<from>-to-<to>.<ext>` and any route through them is found automatically.
2. **Migrate.** Rules cover what renaming cannot express: a method that moved to another class, an instance method that became static, a field that turned into a getter. They ship with the loader and can be extended per instance; see [`loader/src/main/resources/octo/api/README.md`](loader/src/main/resources/octo/api/README.md).
3. **Stand in.** For classes that are simply gone, Octo generates a class of exactly the shape the mod uses. The mod links and runs; calls into the dead API return a default rather than killing the mod at class-load time.

**What this is not.** Mechanical translation cannot invent behaviour. A 1.7.10 mod that registers blocks through a registry that no longer exists will load, run its initialiser, and register nothing. Octo's claim is that the mod runs and that what did not survive is reported, not that a decade-old mod becomes a working modern mod. Mods that only touch API that still exists in some form fare best; deep world-generation and rendering mods fare worst.

</details>

<details>
<summary><b>Mods one version away from the game</b></summary>

The most common problem is small: a current mod on a current game, one version away from the one its author built against. Three things happen, and **none of them happens unless the original has been looked for and genuinely not found**, which is what makes one set of rules correct on every version at once:

1. **A class that survives under another name is redirected to it.** Candidates are listed in [`equivalents.json`](loader/src/main/resources/octo/api/equivalents.json) and tried in order.
2. **A class that is gone outright may be stood in for**, shaped from the mod's own bytecode, so `new ItemStackHandler(9)` gets a class with that constructor.
3. **A mixin aimed at a member that moved is re-aimed**, and one aimed at a member that is genuinely gone is made optional rather than fatal, instead of Mixin's `Critical injection failure` and a dead launch.

Most of the mixin half needs no table, because the answers are in the target class: `lambda$reloadResourcePacks$21` is matched on its stable enclosing method name, an accessor for `entries:Ljava/util/Map;` finds the field by its type when exactly one field has it, and a target whose descriptor drifted is matched on its name when only one method carries it.

</details>

<details>
<summary><b>NeoForge and Forge mods get the real API</b></summary>

Fabric mods depend on Fabric API, which is a mod you install. NeoForge and Forge mods depend on the equivalent half of their loader (`NeoForge.EVENT_BUS`, `ModConfigSpec`, the capability types, every game event), which ships inside the loader. Octo implements the loader half itself (mod list, buses, lifecycle, `@EventBusSubscriber`, configs, Forge 66's `BusGroup` and constructor injection, NeoForge's `FMLLoader`) and, when your mods folder has NeoForge or Forge mods in it, fetches the other half: the project's own `universal` jar for the running game version, from its official Maven repository, cached in `.octo/cache/libraries/`. Those jars expect a game their installer patched. Octo fires the events the patches would have fired where mods depend on them, and bridges the calls they make into patched-in game methods like a translated mod's.

</details>

<details>
<summary><b>Version checks are advisory</b></summary>

Nearly every mod declares a narrow game version, and enforcing it is what makes an old mod "incompatible" even when nothing it calls has changed. Octo treats an unsatisfied *game* version bound as a warning and hands the mod to the translation layer. Bounds *between mods* are still enforced, since those describe APIs the loader cannot fix. `-Docto.strictVersions=true` restores the usual behaviour.

</details>

### The game always starts

If a mod you installed could not be loaded, that is a reason to be told, not a reason to be unable to play. Every failure is collected, written to `.octo/logs/mod-report.txt`, printed to the log, and shown in a window as the game boots:

```
1 mod is not running, and 6 needed adjusting

Not running

  create-1.21.1.jar - its metadata could not be read, so it was not loaded
      fabric.mod.json: malformed JSON: Expected ':' at line 1 column 29

Loaded with changes

  ancientmod - built for Minecraft 1.7.10, translated to run here
```

Everything that could end a launch ends in that report instead: metadata that will not parse, an archive that will not open, dependency resolution rejecting a mod, a mixin that would not apply, a mod that throws while initialising, and every loader API call Octo has not implemented. The window is Octo's own rather than a Minecraft screen, so it works on versions released after Octo. It is not modal and does not hold up the launch.

<details>
<summary><b>And when Minecraft itself crashes</b></summary>

When Minecraft crashes it calls `System.exit(-1)`, which used to take every window with it and leave a launcher dialog saying `Exit code: -1`. Octo takes the exit call itself, a few instructions before the JVM acts on it, so nothing has started shutting down when the crash is shown, and the window stays until you close it. It shows what Minecraft was doing, in its own words; what the failure means in plain language; and what to try next, with the location of Minecraft's own crash report.

A thread dying quietly in the background is recorded but not treated as a crash: mods lose worker threads and the game plays on.

Octo also reads the game's registries back as soon as its start-up claims to have filled them, and names every one that came out empty or without its default entry. That is the real cause behind `Cannot invoke "net.minecraft.core.Holder$Reference.value()" because "this.defaultValue" is null`, which otherwise surfaces seconds later somewhere unrelated.

</details>

<details>
<summary><b>How the four loaders are merged</b></summary>

The upstream sources are vendored as one merged tree in [`upstream/octo`](upstream/octo), laid out by package rather than by project, with provenance in [`upstream/octo/ORIGINS.md`](upstream/octo/ORIGINS.md). The merge happens at the layer that matters: **mods link against concrete classes**, not against "a loader". A Fabric mod imports `net.fabricmc.api.ModInitializer`; a NeoForge mod carries `net.neoforged.fml.common.Mod`. So Octo compiles those upstream sources straight into its own jar and implements the half behind them itself.

| Ecosystem | What is vendored and compiled in | What Octo implements |
|---|---|---|
| Fabric | `net.fabricmc.api.*`, `net.fabricmc.loader.api.*`, the version parser (the whole mod-facing API, verbatim) | `FabricLoaderImpl`, `ModContainer`, `ModMetadata`, `ObjectShare`, `MappingResolver`, the language adapter |
| Quilt | `LanguageAdapterException`, `VersionFormatException` | `QuiltLoader`, `ModContainer`, `ModMetadata`, the QSL entrypoint interfaces |
| NeoForge | `net.neoforged.fml.common.Mod`, `IModBusEvent`, `IExtensionPoint`, `ModConfigEvent`, verbatim | `Dist`, the event bus, the lifecycle events over one `ParallelDispatchEvent`, configs, `ModList`, `LoadingModList`, `ModContainer`, `ModLoadingContext`, `neoforgespi`'s `IModInfo` and `IModFileInfo` |
| Forge | *(reference only)* | `@Mod` covering both the 1.7 and 1.13 shapes, the event bus, both generations of lifecycle event, `ModList`, `LoadingModList`, `ModContainer`, `FMLJavaModLoadingContext`, `forgespi`'s `IModInfo` and `IModFileInfo` |

What is deliberately *not* merged is each loader's bootstrap half: Knot, ModLauncher, SecureJarHandler, Quilt's plugin system. Each expects to own the class loader and the game's entry point, and two of them in one JVM is a contradiction, not a merge conflict. Octo replaces all four with a single transforming class loader that every ecosystem's API sits on top of. That is the only arrangement in which they can share a process, and it is what makes the cross-ecosystem mod list possible.

Because there is a single mod list, `ModList.get().getModContainerById("sodium").get().getModInfo().getVersion()` returns Sodium's real version to a NeoForge mod, out of Sodium's own `fabric.mod.json`.

</details>

## Building from source

You need Git and JDK 17 or newer (CI builds with JDK 21, emitting Java 17 bytecode). The Gradle wrapper downloads the right Gradle for you.

```bash
git clone https://github.com/MilkdromedaStudios/Octo-Loader.git
cd Octo-Loader

./gradlew build                      # compile and run the complete test suite
./gradlew :loader:test               # loader tests only
./gradlew :installer:test            # installer tests only
./gradlew :installer:installerJar    # → installer/build/installer/installer.jar
./gradlew :loader:jar                # → loader/build/libs/octo-loader-1.0.0.jar
```

On Windows, use `gradlew.bat` in place of `./gradlew`. To inspect a mods folder without starting Minecraft:

```bash
java -jar loader/build/libs/octo-loader-1.0.0.jar scan --gameDir /path/to/instance --modsDir /path/to/instance/mods --mcVersion 1.20.1
```

<details>
<summary><b>Contributing, tests and upstream sync</b></summary>

The root project has two Gradle subprojects: `loader` builds the self-contained runtime and command-line scanner; `installer` embeds that loader jar and builds the GUI/client/server installer. Don't edit anything under `upstream/octo` or `build/generated` by hand. Change Octo's own sources, or refresh the merged tree with `python3 scripts/sync-upstreams.py` (needs Python 3, Git and network access).

The vendored loader APIs are checked for updates every day. The [`sync-upstreams` workflow](.github/workflows/sync-upstreams.yml) refreshes the merged tree, records each project's exact commit, runs the complete test suite, and opens a pull request when anything changed.

The tests are not smoke tests. The end-to-end suite compiles six real mods (one per ecosystem, plus a LiteLoader mod and one written against a 2014 API that is never provided again), packs them into real jars, and boots the actual loader against a synthetic game jar. The mixin suite compiles a mod against a widened view of the game, hands the loader the restricted original, and asserts that the mod's `@Inject`, `@Invoker` and access widener all took effect. The transform suites assemble call sites with old descriptors, exactly as a mod from another version arrives, and let the JVM's verifier judge what comes out.

Before submitting a change, run:

```bash
./gradlew build --stacktrace
git diff --check
```

The `build.yml` workflow repeats the build, packages the installer, scans six sample mod formats, and publishes release artifacts from `main`.

</details>

<details>
<summary><b>Project layout</b></summary>

```
loader/                     the loader runtime
  mod/                      the canonical mod model and the six metadata parsers
  discovery/                finding mods, and reading entrypoints out of bytecode
  resolve/                  dependency resolution and load order
  compat/                   eras, mappings, API rules, the version-equivalents table
  transform/                remapping, API migration, class substitution, stand-ins,
                            and the bridges between old and new game APIs
  access/                   access wideners and access transformers, merged
  mixin/                    the mixin service, the one mixin environment, and the
                            version-adaptive target resolver
  bridge/                   per-ecosystem construction and lifecycle, and the
                            client bridges for old renderers and models
  launch/                   the class loader and the launcher
  rebuild/                  unzip, translate, re-jar and cache mods from other versions
  compat/<ecosystem>/       the implementation behind each upstream API
installer/                  installer.jar: client, server, GUI and CLI
upstream/octo/              Fabric, Quilt, Forge and NeoForge sources, merged
                            into one tree by scripts/sync-upstreams.py
docs/                       the logo and other project assets
```

</details>

## Bugs

This is only in development, bugs will be expected and not all mods will be able to be loaded. If you find any bugs, please open a issue or a pr request with fixes if you are tech savy. 

## Licence

Octo Loader's own code is Apache-2.0 (see [`LICENSE`](LICENSE)). The vendored upstream sources keep their own licences: Fabric Loader and Quilt Loader are Apache-2.0, Forge and NeoForge's FancyModLoader are LGPL-2.1. Distributing a binary that combines Apache-2.0 and LGPL-2.1 code is not something to do casually; which files come from where, and at which commit, is in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md). If you plan to redistribute Octo, read that file first.

<p align="center"><sub>Not affiliated with Mojang, Microsoft, FabricMC, QuiltMC, Forge or NeoForged.</sub></p>
