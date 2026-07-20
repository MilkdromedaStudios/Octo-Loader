# Octo-Loader

**One mods folder, every ecosystem.** Octo Loader is a [Fabric](https://fabricmc.net/) mod
for Minecraft 26.2 that inspects every jar in your `mods/` folder — Fabric, Quilt,
NeoForge, Forge, legacy Forge, even Paper/Bukkit plugins — identifies exactly what it
is (loader, version, target Minecraft), translates what can be translated into a real
Fabric mod, and gives you a precise, honest report about the rest. In the spirit of
[Sinytra Connector](https://github.com/Sinytra/Connector), but pointed at Fabric.

## What it can and cannot do

| Ecosystem | Status |
|---|---|
| Fabric | loaded natively by Fabric Loader (Octo just reports it) |
| Quilt (targeting 26.2, no QSL) | ✅ **translated and loaded** — metadata rewrite, bytecode untouched |
| Quilt / NeoForge targeting an older 26.x (26.0, 26.1) | ✅ **API-replaced and loaded** — the documentation of each intervening version is composed into a rename chain and applied to the mod's compiled references; mods using APIs removed along the way are flagged `partial` with the removing version named |
| Quilt (needs QSL) | flagged `partial`, skipped, missing modules named |
| NeoForge (targeting 26.x) | ✅ **translated and loaded** if it only uses the shimmed API subset (`@Mod` entry, mod event bus, lifecycle events); otherwise flagged `partial` with the exact APIs named |
| NeoForge / Forge for Minecraft 1.x | detected + identified; **cannot run** — the report cites the exact documented step where the API chain breaks |
| Legacy Forge (≤1.12.2) and Beta-era mods | detected + identified, report only — the documentation chain reaches back to Beta 1.7.3, so the report explains the era-specific break |
| Paper / Bukkit plugins | detected + identified (name, version, api-version), report only — a Bukkit API layer is a possible future phase |

Every jar gets a row in the startup log table and in `octo-report.md` in your game
directory, with a reason you can act on. Octo Loader never crashes the game over a
mod it can't load.

> **Why can't old-version mods run?** Octo documents every Minecraft version step
> from Beta 1.7.3 to the current release. Within the unobfuscated 26.x window those
> docs are mechanical — renames and removals — so a 26.0 or 26.1 mod is genuinely
> rewritten and loaded on 26.2. Before 26.0 the docs are descriptive: a 1.20.1 Forge
> mod calls into obfuscated 1.20.1 internals that no rename table can bring back, and
> the 26.0 rewrite severed the last mapping bridge. Octo's report names the exact step
> where the chain breaks (The Flattening, the 26.0 rewrite, …) instead of pretending.

## Installation

1. Install Fabric Loader ≥ 0.19.3 for Minecraft 26.2 and put the Octo Loader jar in `mods/`.
2. Drop your foreign mods (Quilt/NeoForge/etc.) into the same `mods/` folder.
3. Launch. Octo translates what it can (written next to the originals as `*.octo.jar`)
   and asks for **one restart** to activate newly translated mods.

### Optional: agent mode (no restarts)

Add this JVM argument to your launcher profile or server start script:

```
-javaagent:mods/octo-loader-<version>.jar
```

The same jar then runs before the game boots, translating new foreign mods and
injecting them **in the same launch** via Fabric's `fabric.addMods` mechanism
(outputs live in `.octo/translated/`). No loader internals are touched in either mode.

## How it works

- **Detection** — every jar is identified by its marker files (`fabric.mod.json`,
  `quilt.mod.json`, `META-INF/neoforge.mods.toml`, `META-INF/mods.toml`, `mcmod.info`,
  `plugin.yml`/`paper-plugin.yml`), including multi-loader jars and nested jar-in-jar
  listings.
- **Knowledge base** — each loader's documented per-era behavior (metadata format,
  mapping namespace, translatability) ships as JSON under
  `src/main/resources/octoloader/knowledge/`; the pipeline consults it to pick a
  strategy and to word its reports.
- **API documentation chain** — `knowledge/minecraft_versions.json` is the ordered
  version timeline from Beta 1.7.3 to today, and `knowledge/apidocs/<version>.json`
  documents what changed between each version and the next: mechanical
  (class/member renames, removals) inside the unobfuscated 26.x window, descriptive
  (report-only) for the obfuscated 1.x and Beta eras.
- **API replacement** — a mod declaring an older target is resolved against the
  timeline, the per-step docs between its version and the running one are composed
  into a single rename chain (e.g. 26.0 → 26.1 → 26.2), and the mod's class files
  are rewritten with it during repacking. Uses of APIs the docs record as removed
  flag the mod `partial` naming the API, the removing version, and the documented
  replacement. Where the chain hits a descriptive step, the mod is reported
  `unsupported version` with that exact step cited.
- **Translation** — ecosystem translators (a `ServiceLoader` SPI) rewrite foreign
  metadata into `fabric.mod.json` and repack the jar with provenance stamped in the
  manifest. Minecraft 26.x is unobfuscated, so same-version NeoForge bytecode runs
  as-is — no remapping required.
- **Shims** — translated NeoForge mods link against Octo's reimplementation of the
  NeoForge API surface (`net.neoforged.*` classes inside Octo's jar), starting with
  the `@Mod` entry contract, the mod event bus, and lifecycle events. A constant-pool
  scan gates every mod against the covered-API index first.
- **Report** — log table + `octo-report.md`, one row per jar: ecosystem, id, version,
  target Minecraft, status (`native` / `translated` / `partial` / `unsupported version` /
  `unsupported ecosystem` / `unrecognized`), and why.

## Building

Requires Java 25. `./gradlew build` builds the mod and runs the tests;
`./gradlew installTestMods runServer` boots a dev server with the in-repo sample
Quilt and NeoForge mods to watch the whole flow end-to-end.

## License

[Apache-2.0](LICENSE)
