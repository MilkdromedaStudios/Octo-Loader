# Where the merged sources came from

`upstream/octo` is one tree built from four upstream checkouts by
`scripts/sync-upstreams.py`. Do not edit it by hand — run the script.

## Upstream revisions

- **fabric-loader** — `b907c5b292fc062d75b6d8bf8255ac200109b992`
- **quilt-loader** — `c5c3b0f6e67bfa2f0744856b277b1d92884c3965`
- **neoforge** — `b6b853518f4c04ac743b83d68606aac06bf72545`
- **forge** — `66d4d888eb9f560a35cd3cc8642f5d8f161fba3d`

## Packages

| Package | Files | From |
| --- | --- | --- |
| `(root)` | 8 | fabric-loader (3), forge (2), neoforge (1), quilt-loader (2) |
| `META-INF` | 2 | forge (1), neoforge (1) |
| `META-INF.quilt-bootstrap` | 1 | quilt-loader (1) |
| `META-INF.services` | 18 | fabric-loader (4), forge (8), neoforge (4), quilt-loader (2) |
| `assets.fabricloader` | 1 | fabric-loader (1) |
| `assets.quilt_loader` | 1 | quilt-loader (1) |
| `changelog` | 31 | quilt-loader (31) |
| `coremods` | 1 | forge (1) |
| `lang` | 31 | neoforge (29), quilt-loader (2) |
| `net.fabricmc.api` | 7 | fabric-loader (7) |
| `net.fabricmc.loader` | 187 | fabric-loader (183), quilt-loader (4) |
| `net.minecraftforge.fml` | 119 | forge (119) |
| `net.minecraftforge.forge` | 3 | forge (3) |
| `net.neoforged.fml` | 264 | neoforge (264) |
| `net.neoforged.neoforgespi` | 33 | neoforge (33) |
| `org.quiltmc.loader` | 380 | quilt-loader (380) |
| `quilt_loader` | 1 | quilt-loader (1) |
| `ui.icon` | 37 | fabric-loader (15), quilt-loader (22) |
| `ui.icon.decoration` | 4 | fabric-loader (4) |

## Files supplied by more than one project

- src/main/java/net/fabricmc/api/ClientModInitializer.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/api/DedicatedServerModInitializer.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/api/EnvType.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/api/Environment.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/api/EnvironmentInterface.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/api/EnvironmentInterfaces.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/api/ModInitializer.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/EntrypointException.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/FabricLoader.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/LanguageAdapter.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/LanguageAdapterException.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/MappingResolver.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/ModContainer.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/ObjectShare.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/SemanticVersion.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/Version.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/VersionParsingException.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/entrypoint/EntrypointContainer.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/entrypoint/PreLaunchEntrypoint.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/metadata/ContactInformation.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/metadata/CustomValue.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/metadata/ModDependency.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/metadata/ModEnvironment.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/metadata/ModMetadata.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/metadata/ModOrigin.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/metadata/Person.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/metadata/version/VersionComparisonOperator.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/metadata/version/VersionInterval.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/api/metadata/version/VersionPredicate.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/FabricLoaderImpl.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/MappingResolverImpl.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/ModContainerImpl.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/entrypoint/EntrypointContainerImpl.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/gui/FabricGuiEntry.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/gui/FabricStatusTree.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/launch/FabricLauncher.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/launch/FabricLauncherBase.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/launch/MappingConfiguration.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/launch/knot/Knot.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/metadata/ModOriginImpl.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/util/DefaultLanguageAdapter.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/util/StringUtil.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/util/version/SemanticVersionImpl.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/util/version/StringVersion.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/util/version/VersionParser.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/impl/util/version/VersionPredicateParser.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/DependencyException.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/FabricLoader.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/ModContainer.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/language/JavaLanguageAdapter.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/language/LanguageAdapter.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/language/LanguageAdapterException.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/launch/common/FabricLauncher.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/launch/common/FabricLauncherBase.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/launch/knot/KnotClient.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/launch/knot/KnotServer.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/launch/server/FabricServerLauncher.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/metadata/EntrypointMetadata.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/metadata/LoaderModMetadata.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/metadata/MapBackedContactInformation.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/util/DefaultLanguageAdapter.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/util/UrlConversionException.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/util/UrlUtil.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/util/version/SemanticVersionImpl.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/util/version/SemanticVersionPredicateParser.java — kept fabric-loader, dropped quilt-loader
- src/main/java/net/fabricmc/loader/util/version/VersionParsingException.java — kept fabric-loader, dropped quilt-loader
- src/main/resources/META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService — kept fabric-loader, dropped quilt-loader
- src/main/resources/META-INF/services/org.spongepowered.asm.service.IMixinService — kept fabric-loader, dropped quilt-loader
- src/main/resources/META-INF/services/org.spongepowered.asm.service.IMixinServiceBootstrap — kept fabric-loader, dropped quilt-loader
- src/main/resources/fabric-installer.launchwrapper.json — kept fabric-loader, dropped quilt-loader
- src/main/resources/net/fabricmc/loader/Messages.properties — kept fabric-loader, dropped quilt-loader
- src/main/resources/net/fabricmc/loader/Messages_es.properties — kept fabric-loader, dropped quilt-loader
- src/main/resources/net/fabricmc/loader/Messages_ja_JP.properties — kept fabric-loader, dropped quilt-loader
- src/main/resources/net/fabricmc/loader/Messages_ko_KR.properties — kept fabric-loader, dropped quilt-loader
- src/main/resources/net/fabricmc/loader/Messages_vi_VN.properties — kept fabric-loader, dropped quilt-loader
- src/main/resources/net/fabricmc/loader/Messages_zh_CN.properties — kept fabric-loader, dropped quilt-loader
- src/main/resources/net/fabricmc/loader/Messages_zh_TW.properties — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/fabric_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/folder_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/jar_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/java_class_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/java_package_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/json_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/lesser_cross_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/level_error_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/level_info_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/level_warn_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/missing_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/package_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/ui/icon/tick_x16.png — kept fabric-loader, dropped quilt-loader
- src/main/resources/META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService — kept fabric-loader, dropped neoforge
