# Third-party notices

Octo Loader vendors the sources of the four loaders it merges. They are in
[`upstream/`](upstream/), unmodified, each with its own licence file alongside
it. This document records what was taken, from where, at which commit, and under
which terms.

## Vendored sources

| Directory | Project | Upstream | Commit | Licence |
|---|---|---|---|---|
| `upstream/fabric-loader` | Fabric Loader | https://github.com/FabricMC/fabric-loader | `b907c5b292fc062d75b6d8bf8255ac200109b992` | Apache-2.0 (`upstream/fabric-loader/LICENSE`) |
| `upstream/quilt-loader` | Quilt Loader | https://github.com/QuiltMC/quilt-loader | `c5c3b0f6e67bfa2f0744856b277b1d92884c3965` | Apache-2.0 (`upstream/quilt-loader/LICENSE`) |
| `upstream/neoforge` | FancyModLoader (NeoForge) | https://github.com/neoforged/FancyModLoader | `b6b853518f4c04ac743b83d68606aac06bf72545` | LGPL-2.1 (`upstream/neoforge/LICENSE.txt`) |
| `upstream/forge` | MinecraftForge, loader modules only | https://github.com/MinecraftForge/MinecraftForge | `66d4d888eb9f560a35cd3cc8642f5d8f161fba3d` | LGPL-2.1 (`upstream/forge/LICENSE.txt`) |

Only the loader modules of MinecraftForge are vendored — `fmlloader`,
`fmlcore`, `javafmllanguage`, `mclanguage`, `lowcodelanguage` and
`forge-transformers`. The `patches/` and `src/` trees, which contain
Minecraft-derived sources, are deliberately excluded: they are not loader code
and are not ours to redistribute.

Build wrappers (`gradlew`, `gradle-wrapper.jar`) and CI configuration were
removed from the vendored copies so they are not mistaken for this project's own.

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
