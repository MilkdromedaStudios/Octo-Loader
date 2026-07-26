package studios.milkdromeda.octo.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.compat.EraDetector;
import studios.milkdromeda.octo.mod.EntrypointSpec;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;
import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.mod.format.FabricMetadataParser;
import studios.milkdromeda.octo.mod.format.ForgeMetadataParser;
import studios.milkdromeda.octo.mod.format.LegacyForgeMetadataParser;
import studios.milkdromeda.octo.mod.format.LiteLoaderMetadataParser;
import studios.milkdromeda.octo.mod.format.MetadataException;
import studios.milkdromeda.octo.mod.format.MetadataParser;
import studios.milkdromeda.octo.mod.format.MetadataParser.Phases;
import studios.milkdromeda.octo.mod.format.QuiltMetadataParser;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Finds every mod in a folder, whatever it is.
 *
 * <p>Order matters. A jar can carry more than one metadata file — Quilt mods
 * usually ship a {@code fabric.mod.json} too, and a mod supporting both Forge
 * and NeoForge ships both TOMLs — so the most specific format wins, and the
 * rest is treated as the same mod rather than a duplicate.
 */
public final class ModDiscoverer {
    private static final OctoLog LOG = OctoLog.of(ModDiscoverer.class);

    private static final Set<String> ARCHIVE_SUFFIXES = Set.of(".jar", ".zip", ".litemod");

    private final List<MetadataParser> parsers = List.of(
            new QuiltMetadataParser(),
            new FabricMetadataParser(),
            new ForgeMetadataParser(ModFormat.NEOFORGE),
            new ForgeMetadataParser(ModFormat.FORGE),
            new LegacyForgeMetadataParser(),
            new LiteLoaderMetadataParser());

    private final ModClassScanner scanner = new ModClassScanner();
    private final EraDetector eraDetector = new EraDetector();
    private final Path extractionDir;

    /**
     * @param extractionDir where nested jars are unpacked; usually {@code .octo/nested}
     *                      under the game directory
     */
    public ModDiscoverer(Path extractionDir) {
        this.extractionDir = extractionDir;
    }

    public List<ModCandidate> discover(List<Path> modDirectories) {
        List<ModCandidate> out = new ArrayList<>();

        for (Path directory : modDirectories) {
            if (!Files.isDirectory(directory)) {
                LOG.debug("mods folder {} does not exist, skipping", directory);
                continue;
            }

            try (Stream<Path> files = Files.list(directory)) {
                List<Path> sorted = files.filter(ModDiscoverer::isCandidateFile).sorted().toList();

                for (Path file : sorted) {
                    out.addAll(discoverFile(file, null));
                }
            } catch (IOException e) {
                LOG.warn("could not read mods folder {}: {}", directory, e.toString());
            }
        }

        return out;
    }

    private static boolean isCandidateFile(Path file) {
        if (Files.isDirectory(file)) {
            // An exploded mod folder is valid in a dev environment.
            return Files.exists(file.resolve("fabric.mod.json")) || Files.exists(file.resolve("quilt.mod.json"))
                    || Files.exists(file.resolve("META-INF/mods.toml")) || Files.exists(file.resolve("mcmod.info"));
        }

        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if (name.endsWith(".disabled")) {
            return false;
        }

        return ARCHIVE_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    /** Reads one file, plus anything nested inside it. */
    public List<ModCandidate> discoverFile(Path file, ModCandidate parent) {
        List<ModCandidate> out = new ArrayList<>();

        try (ModSource source = ModSource.open(file)) {
            ScanResult scan = scanner.scan(source);
            List<ModMetadata> declared = parseMetadata(source);

            if (declared.isEmpty()) {
                ModMetadata recovered = recoverFromBytecode(file, scan);

                if (recovered == null) {
                    LOG.debug("{} contains no mod metadata and no recognisable entrypoint, ignoring", file.getFileName());
                    return out;
                }

                LOG.info("{} has no metadata; recovered {} from its bytecode", file.getFileName(), recovered.id());
                declared = List.of(recovered);
            }

            for (ModMetadata metadata : declared) {
                ModMetadata completed = completeEntrypoints(metadata, scan);
                ModCandidate candidate = new ModCandidate(file, completed, parent);
                candidate.era(eraDetector.detect(completed, scan));

                if (candidate.era() == Era.UNKNOWN) {
                    candidate.note("era could not be determined; loading without translation");
                }

                out.add(candidate);
                out.addAll(extractNested(source, candidate));
            }
        } catch (IOException e) {
            LOG.warn("could not read {}: {}", file, e.toString());
        }

        return out;
    }

    private List<ModMetadata> parseMetadata(ModSource source) {
        for (MetadataParser parser : parsers) {
            if (!parser.matches(source)) {
                continue;
            }

            try {
                List<ModMetadata> parsed = parser.parse(source);

                if (!parsed.isEmpty()) {
                    return parsed;
                }
            } catch (MetadataException e) {
                LOG.warn("{} has a broken {}: {}", source.path().getFileName(),
                        parser.format().metadataPath(), e.getMessage());
            }
        }

        return List.of();
    }

    /**
     * Builds metadata for a jar that has none.
     *
     * <p>Mods from before {@code mcmod.info} existed — and plenty of one-off
     * jars from after it — carry nothing but classes. If the bytecode contains
     * something that is unmistakably a mod entrypoint, that is enough to load it.
     */
    private ModMetadata recoverFromBytecode(Path file, ScanResult scan) {
        if (scan.isEmpty()) {
            return null;
        }

        String id = file.getFileName().toString()
                .replaceAll("(?i)\\.(jar|zip|litemod)$", "")
                .replaceAll("[^A-Za-z0-9_.-]+", "_")
                .toLowerCase(Locale.ROOT);

        ModMetadata.Builder builder = ModMetadata.builder(id, ModFormat.BARE)
                .name(file.getFileName().toString())
                .version("0.0.0")
                .description("Recovered from bytecode: no metadata file was present.");

        return builder.build();
    }

    /**
     * Fills in entrypoints the metadata format does not carry.
     *
     * <p>Forge and NeoForge point at their mod class with an annotation, LiteLoader
     * with an interface, and the ModLoader era with a class naming convention. All
     * three are recovered from the scan and written into the canonical metadata so
     * the bridges downstream see one uniform shape.
     */
    private ModMetadata completeEntrypoints(ModMetadata metadata, ScanResult scan) {
        boolean needsForgeEntrypoint = metadata.format().family() == ModFormat.Family.FORGE
                && metadata.entrypoints(Phases.MOD_CLASS).isEmpty();

        if (!needsForgeEntrypoint && metadata.allEntrypoints().isEmpty() && scan.isEmpty()) {
            return metadata;
        }

        ModMetadata.Builder builder = metadata.toBuilder();

        if (needsForgeEntrypoint) {
            for (var entry : scan.modAnnotations().entrySet()) {
                String declaredId = entry.getValue();

                // A jar may hold several @Mod classes; take the one that claims
                // this mod id, or all of them when none says.
                if (declaredId.isEmpty() || declaredId.equals(metadata.id())) {
                    builder.entrypoint(Phases.MOD_CLASS, new EntrypointSpec(entry.getKey(), null,
                            Phases.MOD_CLASS, "@Mod " + entry.getKey()));
                }
            }

            for (String modLoaderClass : scan.modLoaderClasses()) {
                builder.entrypoint(Phases.MOD_CLASS, new EntrypointSpec(modLoaderClass, null,
                        Phases.MOD_CLASS, "ModLoader " + modLoaderClass));
            }
        }

        if (metadata.entrypoints(Phases.MAIN).isEmpty()) {
            for (String initializer : scan.fabricInitializers()) {
                builder.entrypoint(Phases.MAIN, new EntrypointSpec(initializer, null, Phases.MAIN, initializer));
            }
        }

        if (metadata.entrypoints(Phases.CLIENT).isEmpty()) {
            for (String initializer : scan.clientInitializers()) {
                builder.entrypoint(Phases.CLIENT, new EntrypointSpec(initializer, null, Phases.CLIENT, initializer));
            }
        }

        if (metadata.entrypoints(Phases.SERVER).isEmpty()) {
            for (String initializer : scan.serverInitializers()) {
                builder.entrypoint(Phases.SERVER, new EntrypointSpec(initializer, null, Phases.SERVER, initializer));
            }
        }

        if (metadata.entrypoints(Phases.PRELAUNCH).isEmpty()) {
            for (String initializer : scan.preLaunchInitializers()) {
                builder.entrypoint(Phases.PRELAUNCH, new EntrypointSpec(initializer, null, Phases.PRELAUNCH, initializer));
            }
        }

        for (String liteMod : scan.liteMods()) {
            builder.entrypoint(Phases.LITEMOD, new EntrypointSpec(liteMod, null, Phases.LITEMOD, liteMod));
            builder.side(Side.CLIENT);
        }

        return builder.build();
    }

    /**
     * Unpacks jar-in-jar payloads. Fabric and Quilt list them in the metadata;
     * Forge and NeoForge put them under {@code META-INF/jarjar/}.
     */
    private List<ModCandidate> extractNested(ModSource source, ModCandidate parent) {
        Set<String> paths = new LinkedHashSet<>(parent.metadata().nestedJars());

        for (String entry : source.entries()) {
            if (entry.startsWith("META-INF/jarjar/") && entry.endsWith(".jar")) {
                paths.add(entry);
            }
        }

        if (paths.isEmpty()) {
            return List.of();
        }

        List<ModCandidate> out = new ArrayList<>();

        for (String path : paths) {
            byte[] bytes = source.read(path).orElse(null);

            if (bytes == null) {
                LOG.warn("{} declares nested jar {} which is not in the archive", parent.id(), path);
                continue;
            }

            try {
                Path target = extractionDir.resolve(parent.id()).resolve(path.replace('/', '_'));
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                out.addAll(discoverFile(target, parent));
            } catch (IOException e) {
                LOG.warn("could not unpack {} from {}: {}", path, parent.id(), e.toString());
            }
        }

        return out;
    }
}
