package studios.milkdromeda.octo.discovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

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
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Finds every mod in a folder, whatever it is.
 *
 * <p>Order matters. A jar can carry more than one metadata file — Quilt mods
 * usually ship a {@code fabric.mod.json} too, and a mod supporting both Forge
 * and NeoForge ships both TOMLs — so the most specific format wins, and the
 * rest is treated as the same mod rather than a duplicate.
 *
 * <p>Every file that is looked at leaves a verdict in the {@link Discovery},
 * including the ones that produced nothing. A jar that is silently ignored is
 * indistinguishable, from the player's side, from a loader that does not work.
 */
public final class ModDiscoverer {
    private static final OctoLog LOG = OctoLog.of(ModDiscoverer.class);

    private static final Set<String> ARCHIVE_SUFFIXES = Set.of(".jar", ".zip", ".litemod");

    /** The metadata files that make an unpacked directory a mod rather than a folder. */
    private static final List<String> EXPLODED_MARKERS = List.of(
            "fabric.mod.json", "quilt.mod.json", "riftmod.json", "litemod.json",
            "META-INF/mods.toml", "META-INF/neoforge.mods.toml", "mcmod.info");

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
    /** The loader the folder's single-loader jars are for, which multi-loader jars are read as. */
    private ModFormat preferred;

    /**
     * @param extractionDir where nested jars are unpacked; usually {@code .octo/nested}
     *                      under the game directory
     */
    public ModDiscoverer(Path extractionDir) {
        this.extractionDir = extractionDir;
    }

    public Discovery discover(List<Path> modDirectories) {
        Discovery discovery = new Discovery(modDirectories);

        for (Path directory : modDirectories) {
            if (!Files.isDirectory(directory)) {
                LOG.warn("mods folder {} does not exist", directory);
                continue;
            }

            try (Stream<Path> files = Files.list(directory)) {
                List<Path> sorted = files.filter(ModDiscoverer::isCandidateFile).sorted().toList();
                LOG.info("{} contains {} file(s) to inspect", directory, sorted.size());
                preferred = preferredFormat(sorted);

                for (Path file : sorted) {
                    discovery.add(discoverFile(file, null, discovery));
                }
            } catch (IOException e) {
                // Not survivable in the way one bad jar is: nothing in this folder
                // was seen, so the count that follows means nothing.
                throw new IllegalStateException("could not read the mods folder " + directory, e);
            }
        }

        return discovery;
    }

    private static boolean isCandidateFile(Path file) {
        if (Files.isDirectory(file)) {
            // An exploded mod folder is valid in a dev environment.
            return EXPLODED_MARKERS.stream().anyMatch(marker -> Files.exists(file.resolve(marker)));
        }

        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if (name.endsWith(".disabled")) {
            return false;
        }

        return ARCHIVE_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    /** Reads one file, plus anything nested inside it. */
    public List<ModCandidate> discoverFile(Path file, ModCandidate parent) {
        return discoverFile(file, parent, new Discovery(List.of()));
    }

    private List<ModCandidate> discoverFile(Path file, ModCandidate parent, Discovery discovery) {
        return discoverFile(file, parent, discovery, false);
    }

    /**
     * @param inLibrary whether the file came out of a library container, where
     *                  a jar that is not a mod is one of the library's own
     */
    private List<ModCandidate> discoverFile(Path file, ModCandidate parent, Discovery discovery, boolean inLibrary) {
        List<ModCandidate> out = new ArrayList<>();
        boolean nested = parent != null || inLibrary;

        try (ModSource source = ModSource.open(file)) {
            ScanResult scan = scanner.scan(source);
            List<ModMetadata> declared = parseMetadata(source, discovery, nested);

            if (declared == null) {
                // Broken metadata, already recorded. The jar is a mod; it is just
                // not a readable one, and pretending it is a library would hide that.
                return out;
            }

            if (declared.isEmpty()) {
                ModMetadata recovered = recoverFromBytecode(file, scan);

                if (recovered == null) {
                    if (!nested && isLibraryContainer(source)) {
                        // Kotlin for Forge: no mod of its own at the top, the
                        // Kotlin standard library and its language loader
                        // inside. Every Kotlin mod needs those classes.
                        LOG.info("{} is a library; its jars go on the class path", file.getFileName());
                        discovery.library(file, null, false);
                        out.addAll(extractContainer(source, file, discovery));
                        return out;
                    }

                    if (inLibrary) {
                        discovery.library(file, null);
                        return out;
                    }

                    if (parent != null) {
                        // A jar a mod shipped inside itself and that is not a mod
                        // is the mod's own library. Dropping it is what made
                        // Create load and then die on Registrate, which it
                        // bundles exactly this way. It goes on the class path
                        // rather than through the mod machinery: it has no
                        // metadata, no entrypoint and nothing to dispatch to.
                        LOG.info("{}: keeping bundled library {}", parent.id(), file.getFileName());
                        discovery.library(file, parent.id());
                        return out;
                    }

                    LOG.debug("{} contains no mod metadata and no recognisable entrypoint, ignoring",
                            file.getFileName());
                    discovery.reject(file, Discovery.Verdict.NOT_A_MOD, nested,
                            "no metadata file and no mod entrypoint in its classes");
                    return out;
                }

                LOG.info("{} has no metadata; recovered {} from its bytecode", file.getFileName(), recovered.id());
                declared = List.of(recovered);
            }

            List<String> ids = new ArrayList<>();

            for (ModMetadata metadata : declared) {
                ModMetadata completed = completeEntrypoints(metadata, scan);
                ModCandidate candidate = new ModCandidate(file, completed, parent);
                candidate.era(eraDetector.detect(completed, scan));
                candidate.namespace(scan.bytecodeNamespace());
                candidate.bytecode(scan.index());

                if (candidate.era() == Era.UNKNOWN) {
                    candidate.note("era could not be determined; loading without translation");
                }

                ids.add(candidate.id());
                out.add(candidate);
                out.addAll(extractNested(source, candidate, discovery));
            }

            discovery.accept(file, nested, ids);
        } catch (Throwable e) {
            // A truncated download, a jar with a class ASM will not parse, a zip
            // bomb: whatever it is, it costs the player that one file. Discovery
            // keeps going so that the report at the end covers the whole folder
            // rather than stopping at the first bad entry — the launcher decides
            // afterwards whether a bad entry is worth refusing to start over.
            Failures.rethrowIfFatal(e);
            LOG.error("could not read {}: {}", file.getFileName(), Failures.describe(e));
            OctoLog.detail(e);
            discovery.reject(file, Discovery.Verdict.UNREADABLE, nested, Failures.describe(e));
        }

        return out;
    }

    /**
     * @return the mods declared in this archive, or {@code null} when a metadata
     *         file is present but unreadable
     */
    /**
     * Which loader the folder is for, judged by the jars that are for only one.
     *
     * <p>A multi-loader jar — Collective ships a {@code fabric.mod.json}, a
     * {@code mods.toml} and a {@code neoforge.mods.toml} side by side — has to be
     * read as one of them, and the fixed order used to make that Fabric every
     * time. In a Forge folder that ran Collective's Fabric code against a Fabric
     * API nothing had installed, and Collective failed where its Forge half
     * would have run. The jars that only speak one loader say what the folder
     * is; only the metadata file names are read for this, never the jar's
     * classes.
     *
     * @return the most common single loader, or {@code null} when nothing says
     */
    private ModFormat preferredFormat(List<Path> files) {
        java.util.Map<ModFormat, Integer> votes = new java.util.EnumMap<>(ModFormat.class);

        for (Path file : files) {
            List<ModFormat> formats = new ArrayList<>();

            try (ModSource source = ModSource.open(file)) {
                for (MetadataParser parser : parsers) {
                    if (parser.matches(source)) {
                        formats.add(parser.format());
                    }
                }
            } catch (IOException | RuntimeException e) {
                continue;
            }

            // A Quilt mod carrying a fabric.mod.json too still speaks one loader.
            if (formats.size() == 2 && formats.contains(ModFormat.QUILT) && formats.contains(ModFormat.FABRIC)) {
                formats.remove(ModFormat.FABRIC);
            }

            if (formats.size() == 1) {
                votes.merge(formats.get(0), 1, Integer::sum);
            }
        }

        ModFormat best = null;
        int bestVotes = 0;

        for (java.util.Map.Entry<ModFormat, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > bestVotes) {
                best = vote.getKey();
                bestVotes = vote.getValue();
            }
        }

        if (best != null) {
            LOG.debug("the folder is mostly {} mods; jars for several loaders are read as {}", best, best);
        }

        return best;
    }

    /** The parsers to try on one jar: the folder's own loader first, then the usual order. */
    private List<MetadataParser> parserOrder(ModSource source) {
        if (preferred == null) {
            return parsers;
        }

        for (MetadataParser parser : parsers) {
            if (parser.format() == preferred && parser.matches(source)) {
                List<MetadataParser> ordered = new ArrayList<>();
                ordered.add(parser);

                for (MetadataParser other : parsers) {
                    if (other != parser) {
                        ordered.add(other);
                    }
                }

                return ordered;
            }
        }

        return parsers;
    }

    private List<ModMetadata> parseMetadata(ModSource source, Discovery discovery, boolean nested) {
        List<String> broken = new ArrayList<>();

        for (MetadataParser parser : parserOrder(source)) {
            if (!parser.matches(source)) {
                continue;
            }

            try {
                List<ModMetadata> parsed = parser.parse(source);

                if (!parsed.isEmpty()) {
                    return parsed;
                }

                broken.add(parser.format().metadataPath() + " declares no mods");
            } catch (MetadataException e) {
                LOG.error("{} has a broken {}: {}", source.path().getFileName(),
                        parser.format().metadataPath(), e.getMessage());
                broken.add(parser.format().metadataPath() + ": " + e.getMessage());
            }
        }

        if (broken.isEmpty()) {
            return List.of();
        }

        discovery.reject(source.path(), Discovery.Verdict.BROKEN_METADATA, nested, String.join("; ", broken));
        return null;
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
     * Whether a jar with no mod in it is a library for mods: Forge and
     * NeoForge mark those in the manifest ({@code FMLModType: LIBRARY},
     * {@code GAMELIBRARY}, {@code LANGPROVIDER}), and bundle what they carry
     * under {@code META-INF/jarjar/}.
     */
    private static boolean isLibraryContainer(ModSource source) {
        boolean marked = source.read("META-INF/MANIFEST.MF")
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8).contains("FMLModType:"))
                .orElse(false);
        return marked || !jarJarPaths(source).isEmpty();
    }

    /**
     * The jars Forge's and NeoForge's jar-in-jar carry: everything under
     * {@code META-INF/jarjar/}, and whatever its {@code metadata.json} names
     * elsewhere. MixinSquared's Forge jar keeps the library itself at
     * {@code META-INF/jars/}, where Fabric would, and lists it there; read
     * from the folder alone, every mod bundling it lost MixinSquared's API,
     * and the game's first class with one of its mixins would not load.
     */
    private static Set<String> jarJarPaths(ModSource source) {
        Set<String> paths = new LinkedHashSet<>();

        for (String entry : source.entries()) {
            if (entry.startsWith("META-INF/jarjar/") && entry.endsWith(".jar")) {
                paths.add(entry);
            }
        }

        source.read("META-INF/jarjar/metadata.json").ifPresent(bytes -> {
            try {
                JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));

                if (root instanceof JsonObject object && object.get("jars") instanceof JsonArray jars) {
                    for (JsonElement jar : jars) {
                        if (jar instanceof JsonObject entry && entry.get("path") instanceof JsonPrimitive path
                                && path.isString() && path.getAsString().endsWith(".jar")) {
                            paths.add(path.getAsString());
                        }
                    }
                }
            } catch (RuntimeException e) {
                LOG.debug("{}: unreadable jar-in-jar metadata: {}", source.path().getFileName(), e.toString());
            }
        });

        return paths;
    }

    /** A library container's nested jars: each a mod if it is one, else part of the library. */
    private List<ModCandidate> extractContainer(ModSource source, Path container, Discovery discovery) {
        List<ModCandidate> out = new ArrayList<>();
        String name = container.getFileName().toString().replaceAll("\\.jar$", "");

        for (String entry : jarJarPaths(source)) {
            byte[] bytes = source.read(entry).orElse(null);

            if (bytes == null) {
                continue;
            }

            try {
                Path target = extractionDir.resolve("library-" + name).resolve(entry.replace('/', '_'));

                if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes);
                }

                out.addAll(discoverFile(target, null, discovery, true));
            } catch (IOException e) {
                LOG.error("could not unpack {} from {}: {}", entry, container.getFileName(), e.toString());
                discovery.reject(Path.of(entry), Discovery.Verdict.UNREADABLE, true,
                        "nested inside " + container.getFileName() + ": " + e);
            }
        }

        return out;
    }

    /**
     * Unpacks jar-in-jar payloads. Fabric and Quilt list them in the metadata;
     * Forge and NeoForge put them under {@code META-INF/jarjar/}.
     */
    private List<ModCandidate> extractNested(ModSource source, ModCandidate parent, Discovery discovery) {
        Set<String> paths = new LinkedHashSet<>(parent.metadata().nestedJars());
        paths.addAll(jarJarPaths(source));

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

                // Only write when it is not already there. Fabric API alone is
                // forty of these, and re-extracting every one of them on every
                // launch was most of the ten seconds before the game appeared.
                // Size is enough of a check: these come out of a jar whose own
                // entry would have to change for the bytes to differ, and a
                // changed mod arrives as a different file in the mods folder.
                if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes);
                } else {
                    LOG.debug("{}: reusing the already-unpacked {}", parent.id(), path);
                }

                out.addAll(discoverFile(target, parent, discovery));
            } catch (IOException e) {
                // A nested jar that cannot be unpacked is a mod the player will
                // not have: Fabric API is forty of these, and losing one quietly
                // is how a mod ends up half-present.
                LOG.error("could not unpack {} from {}: {}", path, parent.id(), e.toString());
                discovery.reject(Path.of(path), Discovery.Verdict.UNREADABLE, true,
                        "nested inside " + parent.id() + ": " + e);
            }
        }

        return out;
    }
}
