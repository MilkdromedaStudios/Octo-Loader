# Third-party notices

Octo Loader vendors the sources of the four loaders it merges. They are in
[`upstream/octo`](upstream/octo), unmodified in content but merged into one
source tree laid out by package rather than by project. This document records
what was taken, from where, at which commit, and under which terms.

## Vendored sources

Every file below lives under `upstream/octo/src/main/{java,resources}`, merged
by [`scripts/sync-upstreams.py`](scripts/sync-upstreams.py). Which project
supplied which package — and which files were supplied by more than one, where
the first project listed here wins — is recorded per package in
[`upstream/octo/ORIGINS.md`](upstream/octo/ORIGINS.md).

| Project | Upstream | Commit | Licence |
|---|---|---|---|
| Fabric Loader | https://github.com/FabricMC/fabric-loader | `b907c5b292fc062d75b6d8bf8255ac200109b992` | Apache-2.0 (`upstream/octo/LICENSES/fabric-loader.LICENSE`) |
| Quilt Loader | https://github.com/QuiltMC/quilt-loader | `c5c3b0f6e67bfa2f0744856b277b1d92884c3965` | Apache-2.0 (`upstream/octo/LICENSES/quilt-loader.LICENSE`) |
| FancyModLoader (NeoForge) | https://github.com/neoforged/FancyModLoader | `b6b853518f4c04ac743b83d68606aac06bf72545` | LGPL-2.1 (`upstream/octo/LICENSES/neoforge.LICENSE`) |
| MinecraftForge, loader modules only | https://github.com/MinecraftForge/MinecraftForge | `66d4d888eb9f560a35cd3cc8642f5d8f161fba3d` | LGPL-2.1 (`upstream/octo/LICENSES/forge.LICENSE`) |

Only the loader modules of MinecraftForge are vendored — `fmlloader`,
`fmlcore`, `javafmllanguage`, `mclanguage`, `lowcodelanguage` and
`forge-transformers`. The `patches/` and `src/` trees, which contain
Minecraft-derived sources, are deliberately excluded: they are not loader code
and are not ours to redistribute.

Only shipped source roots are vendored. Each project's tests, benchmarks, build
wrappers, CI configuration and Gradle plumbing are not copied: nothing here
reads them, they were the bulk of the four checkouts, and keeping them invites
the mistake of taking them for this project's own.

## What is compiled into the distributed jar

Vendoring a source tree and shipping its bytecode are different acts, so this is
the list that matters for the released `octo-loader.jar` and `installer.jar`.

**From Fabric Loader (Apache-2.0), compiled in verbatim:**

- `net/fabricmc/api/**` — `ModInitializer`, `ClientModInitializer`,
  `DedicatedServerModInitializer`, `EnvType`, `Environment`, `EnvironmentInterface`
- `net/fabricmc/loader/api/**` — `FabricLoader`, `ModContainer`, `ModMetadata`,
  `Version`, `SemanticVersion`, `ObjectShare`, `MappingResolver`, the entrypoint
  and metadata sub-packages
- `net/fabricmc/loader/impl/util/version/**` — the version parser
- `net/fabricmc/loader/util/version/**` (from the `legacyJava` source set)

**From FancyModLoader (LGPL-2.1), compiled in verbatim:**

- `net/neoforged/fml/common/Mod.java`
- `net/neoforged/fml/event/IModBusEvent.java`
- `net/neoforged/fml/IExtensionPoint.java`
- `net/neoforged/neoforgespi/language/ModFileScanData.java` — the table of what a
  mod's own bytecode said about itself, which mods read and compare types against

**From Quilt Loader (Apache-2.0), compiled in verbatim:**

- `org/quiltmc/loader/api/LanguageAdapterException.java`
- `org/quiltmc/loader/api/VersionFormatException.java`

**From MinecraftForge:** nothing is compiled in. The vendored modules are used as
the reference for Forge's contracts; the classes Octo ships in
`net.minecraftforge.*` are its own implementations of those contracts.

Everything else under `net.fabricmc.*`, `org.quiltmc.*`, `net.minecraftforge.*`
and `net.neoforged.*` in the built jar is Octo's own code, written to match the
published signatures so that mods compiled against the real loaders link against
it. Those files carry Octo's Apache-2.0 header and live in `loader/src/main/java`.

The upstream licence texts are copied into the built jar under
`META-INF/licenses/`.

## Libraries shaded into the distributed jar

The loader jar has to start before any dependency resolver exists, so its
third-party dependencies are shaded in rather than downloaded. None of them are
modified.

| Library | Coordinates | Licence | Why it is there |
|---|---|---|---|
| ASM | `org.ow2.asm:asm{,-tree,-commons,-analysis,-util}` | BSD-3-Clause | Every bytecode rewrite: remapping, API migration, access widening, stand-in generation |
| Mixin (FabricMC fork) | `net.fabricmc:sponge-mixin` | MIT | Applying mods' mixins. Fabric's fork rather than `org.spongepowered:mixin` because the Sponge artifact on Maven Central stopped at 0.8.5, and mods are compiled against the fork |
| MixinExtras | `io.github.llamalad7:mixinextras-common` | MIT | `@WrapOperation` and the rest of the injectors current mods are written against; the loader initialises it, as Fabric Loader does |
| Gson | `com.google.code.gson:gson` | Apache-2.0 | Reading `fabric.mod.json`, `quilt.mod.json`, `litemod.json` and mixin configs |
| night-config | `com.electronwill.night-config:{core,toml}` | LGPL-3.0 | Reading `mods.toml` and `neoforge.mods.toml` |

Mixin's own `META-INF/services` entries are dropped when it is shaded, because
they bind it to LaunchWrapper and ModLauncher; Octo registers its own mixin
service in their place.

## Licence compatibility

This needs stating plainly rather than being buried.

Octo Loader's own code is Apache-2.0. Two of the four vendored projects
(FancyModLoader, MinecraftForge) are LGPL-2.1, and **Apache-2.0 and LGPL-2.1 are
not compatible in the direction that matters**: the Apache licence's patent
termination clause is an additional restriction that GPLv2-family licences do
not permit. LGPL-2.1 does allow relicensing under LGPL-3.0 or later ("either
version 2.1 of the License, or (at your option) any later version"), and
LGPL-3.0 *is* compatible with Apache-2.0, which is the usual route through this.

What that means in practice:

- The repository as it stands — Apache-2.0 code alongside LGPL sources, each
  under its own licence — is fine.
- The **built jar** currently contains one file of LGPL-2.1 origin
  (`net/neoforged/fml/common/Mod.java`, thirteen lines of annotation
  declaration) combined with Apache-2.0 code. Before redistributing that binary,
  either take the LGPL-2.1-or-later upgrade path to LGPL-3.0 for the combined
  work, or replace that one file with an independent declaration of the same
  annotation, which would leave nothing LGPL in the output at all.
- Nothing here is legal advice. If you are shipping this, get your own.

## Trademarks

Minecraft is a trademark of Mojang AB. Fabric, Quilt, Forge and NeoForge are the
marks of their respective projects. This project is not affiliated with, endorsed
by, or supported by any of them; the names are used only to identify the formats
and APIs it is compatible with.
