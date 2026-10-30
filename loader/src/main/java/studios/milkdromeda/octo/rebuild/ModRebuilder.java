package studios.milkdromeda.octo.rebuild;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.compat.Era.MappingNamespace;
import studios.milkdromeda.octo.compat.TimeCapsule;
import studios.milkdromeda.octo.compat.mapping.MappingProvider;
import studios.milkdromeda.octo.compat.mapping.MappingRegistry;
import studios.milkdromeda.octo.compat.mapping.MappingSet;
import studios.milkdromeda.octo.compat.mapping.VersionDiff;
import studios.milkdromeda.octo.launch.LaunchContext;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.transform.TransformContext;
import studios.milkdromeda.octo.transform.TransformPipeline;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Rebuilds a mod made for another Minecraft into one made for this one.
 *
 * <p>The jar is opened, every class in it is rewritten — renamed into the names
 * this game uses, then carried across the API changes the rule packs describe —
 * along with the files that name game classes as text: mixin refmaps, access
 * wideners and access transformers. The result is packed into a new jar under
 * {@code .octo/cache/rebuilt} and that jar is what gets loaded. The original
 * in the mods folder is never touched.
 *
 * <p>The rebuilt jar is kept. Its name carries a hash of the original and of
 * everything that went into the rebuild — the Octo version, the game version,
 * the tables and rules used — so the next launch with the same mod on the same
 * game finds it and loads it straight away, and any change to any of those
 * rebuilds it again rather than reusing something stale.
 */
public final class ModRebuilder {
    private static final OctoLog LOG = OctoLog.of(ModRebuilder.class);

    /** Bumped whenever what a rebuild produces changes, so old caches are not reused. */
    private static final int FORMAT = 10;

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private final LaunchContext context;
    private final TimeCapsule timeCapsule;
    private final MappingProvider mappings;
    private final Path cacheDirectory;

    public ModRebuilder(LaunchContext context, TimeCapsule timeCapsule, MappingProvider mappings) {
        this.context = context;
        this.timeCapsule = timeCapsule;
        this.mappings = mappings;
        this.cacheDirectory = context.octoDir().resolve("cache").resolve("rebuilt");
    }

    /** What happened to one mod. */
    public record Result(Path jar, boolean rebuilt, boolean fromCache, String summary) {
        static Result untouched() {
            return new Result(null, false, false, "");
        }
    }

    /** A plan: which table to rename through, and which rules to apply after. */
    /**
     * @param renames what the game renamed after the newest published table,
     *                applied as a second pass over what {@code names} produced
     */
    private record Plan(MappingSet names, MappingSet renames, TransformPipeline rules, String recipe,
            String description) {
    }

    /**
     * Rebuilds a mod if it needs it.
     *
     * @return the jar to load in place of the original, or an untouched result
     *         when the mod already speaks this game's language
     */
    public Result prepare(LoadedMod mod) {
        if (!context.compat().translateOldMods() || mod.isBuiltin()) {
            return Result.untouched();
        }

        Optional<Plan> plan;

        try {
            plan = plan(mod);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("{}: could not plan its translation: {}", mod.id(), Failures.describe(e));
            OctoLog.detail(e);
            return Result.untouched();
        }

        if (plan.isEmpty()) {
            return Result.untouched();
        }

        Path source = mod.effectivePath();

        if (!Files.isRegularFile(source)) {
            return Result.untouched();
        }

        try {
            String key = cacheKey(source, plan.get().recipe());
            Path target = cacheDirectory.resolve(safe(mod.id()) + "-" + safe(mod.metadata().version().raw()) + "-"
                    + key + ".jar");

            if (Files.isRegularFile(target)) {
                LOG.info("{}: using the rebuilt copy from the cache ({})", mod.id(), plan.get().description());
                return new Result(target, true, true, plan.get().description());
            }

            long started = System.nanoTime();
            int classes = rebuild(source, target, plan.get(), mod);
            LOG.info("{}: rebuilt {} class(es) for this Minecraft in {} ms ({})", mod.id(), classes,
                    (System.nanoTime() - started) / 1_000_000, plan.get().description());
            return new Result(target, true, false, plan.get().description());
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("{}: could not be rebuilt, so it loads as it is: {}", mod.id(), Failures.describe(e));
            OctoLog.detail(e);
            return Result.untouched();
        }
    }

    /**
     * A library a mod bundles, rebuilt with that mod's plan.
     *
     * <p>It was compiled against the same Minecraft and the same loader API as
     * the mod around it, so it needs the same translation; left as it was,
     * Registrate inside Create for 1.21.1 named a NeoForge class 26.3 no
     * longer has as the superclass of its item model provider, and the game's
     * own bootstrap failed on it.
     */
    public Result prepareLibrary(LoadedMod owner, Path library) {
        if (!context.compat().translateOldMods() || owner.isBuiltin() || !Files.isRegularFile(library)) {
            return Result.untouched();
        }

        try {
            Optional<Plan> plan = plan(owner);

            if (plan.isEmpty()) {
                return Result.untouched();
            }

            String name = library.getFileName().toString().replaceFirst("\\.jar$", "");
            Path target = cacheDirectory.resolve(safe(owner.id()) + "-library-" + safe(name) + "-"
                    + cacheKey(library, plan.get().recipe()) + ".jar");

            if (Files.isRegularFile(target)) {
                return new Result(target, true, true, plan.get().description());
            }

            long started = System.nanoTime();
            int classes = rebuild(library, target, plan.get(), owner);
            LOG.info("{}: rebuilt {} class(es) of its bundled {} in {} ms", owner.id(), classes,
                    library.getFileName(), (System.nanoTime() - started) / 1_000_000);
            return new Result(target, true, false, plan.get().description());
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("{}: its bundled {} could not be rebuilt, so it loads as it is: {}", owner.id(),
                    library.getFileName(), Failures.describe(e));
            OctoLog.detail(e);
            return Result.untouched();
        }
    }

    /**
     * What a rebuild of this mod would do, without fetching or building
     * anything — for {@code octo scan}.
     *
     * @return a description, or {@code null} when the mod would load as it is
     */
    public String describe(LoadedMod mod) {
        if (!context.compat().translateOldMods() || mod.isBuiltin()
                || timeCapsule.runtimeEra() != Era.UNOBFUSCATED || mod.era() == Era.UNOBFUSCATED) {
            return null;
        }

        MappingNamespace namespace = mod.candidate().namespace();
        String builtFor = builtFor(mod);
        boolean olderMojang = namespace == MappingNamespace.MOJANG && mod.era().isKnown()
                && mod.era().isOlderThan(Era.UNOBFUSCATED) && builtFor != null;

        if (namespace != MappingNamespace.INTERMEDIARY && namespace != MappingNamespace.SRG && !olderMojang) {
            return null;
        }

        return "rebuild: " + describe(namespace) + (builtFor == null ? "" : " " + builtFor)
                + " -> today's names, cached in .octo/cache/rebuilt";
    }

    // ------------------------------------------------------------------ plan

    private Optional<Plan> plan(LoadedMod mod) {
        Era runtimeEra = timeCapsule.runtimeEra();
        Era modEra = mod.era();
        MappingNamespace namespace = mod.candidate().namespace();
        String builtFor = builtFor(mod);
        List<String> recipe = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        MappingSet names = MappingSet.EMPTY;
        MappingSet renames = MappingSet.EMPTY;

        // A mod that never names a game class has nothing to translate, whatever
        // era its class files suggest: a maths library compiled for Java 8 is
        // not a 1.12 mod.
        if (namespace == MappingNamespace.UNKNOWN) {
            return Optional.empty();
        }

        // Since 26.1 the game carries Mojang's names, so a mod speaking any other
        // set of names — or Mojang's own from an older release — is renamed. A
        // mod already built for this era is never renamed, whatever stray
        // numbered names it carries.
        if (runtimeEra == Era.UNOBFUSCATED && modEra != Era.UNOBFUSCATED) {
            boolean olderMojang = namespace == MappingNamespace.MOJANG && modEra.isKnown()
                    && modEra.isOlderThan(Era.UNOBFUSCATED) && builtFor != null;

            if (namespace == MappingNamespace.INTERMEDIARY || namespace == MappingNamespace.SRG || olderMojang) {
                String version = builtFor != null ? builtFor
                        : namespace == MappingNamespace.SRG ? "1.20.1" : null;
                Optional<MappingSet> table = mappings.toCurrent(namespace, version);

                if (table.isPresent()) {
                    names = table.get();
                    recipe.add("names=" + table.get().name());
                    steps.add(describe(namespace) + (version == null ? "" : " " + version) + " -> today's names");
                    renames = gameRenames(version);

                    if (!renames.isEmpty()) {
                        recipe.add("renames=" + renames.name() + "#" + renames.size());
                        steps.add(renames.size() + " later rename(s) found by comparing jars");
                    }
                } else {
                    steps.add("no " + describe(namespace) + " table could be had, names left as they are");
                }
            }
        }

        // Tables the player supplied in .octo/mappings answer whatever the
        // published ones do not.
        MappingSet supplied = MappingRegistry.get().routeFor(modEra);

        if (!supplied.isEmpty()) {
            names = names.orElse(supplied);
            recipe.add("supplied=" + supplied.name());
        }

        TransformPipeline rules = modEra.isKnown() && runtimeEra.isKnown() && modEra.isOlderThan(runtimeEra)
                ? timeCapsule.rulesFor(mod) : TransformPipeline.empty();

        if (!rules.isEmpty()) {
            recipe.add("rules=" + rules + "@" + timeCapsule.rulesFingerprint(modEra));
            steps.add("API rules " + modEra + " -> " + runtimeEra);
        }

        if (names.isEmpty() && rules.isEmpty()) {
            return Optional.empty();
        }

        recipe.add("octo=" + OctoRuntime.VERSION);
        recipe.add("format=" + FORMAT);
        recipe.add("game=" + context.minecraftVersion());
        return Optional.of(new Plan(names, renames, rules, String.join(";", recipe), String.join(", ", steps)));
    }

    /**
     * What the running game renamed that no published table knows: since
     * 1.21.11, the last release with tables, and since the mod's own release
     * for members intermediary lost track of.
     */
    private MappingSet gameRenames(String release) {
        String side = context.side().name().toLowerCase(Locale.ROOT);
        MappingSet latest = mappings.latestToGame(context.gameJars(), context.minecraftVersion(), side)
                .orElse(MappingSet.EMPTY);
        MappingSet own = release == null || release.equals(MappingProvider.LATEST_INTERMEDIARY) ? MappingSet.EMPTY
                : mappings.releaseToGame(release, context.gameJars(), context.minecraftVersion(), side)
                        .orElse(MappingSet.EMPTY);

        // That comparison is keyed in the release's own Mojang names, and is
        // applied after the published tables have moved the mod to 1.21.11's.
        // What those tables renamed is theirs; what is left is what they lost.
        if (!own.isEmpty()) {
            own = own.unchangedBy(mappings.toCurrent(MappingNamespace.MOJANG, release).orElse(MappingSet.EMPTY));
        }

        return latest.orElse(own);
    }

    private static String describe(MappingNamespace namespace) {
        return switch (namespace) {
            case INTERMEDIARY -> "Fabric intermediary";
            case SRG -> "Forge SRG";
            case MOJANG -> "Mojang's names of";
            default -> namespace.name().toLowerCase(Locale.ROOT);
        };
    }

    /**
     * The release a mod was built for, as precisely as its metadata says: a
     * legacy {@code mcversion}, or the lower end of a Minecraft range closed at
     * the top. Only a real release id is accepted, because it is about to be
     * looked up in Mojang's version list.
     */
    static String builtFor(LoadedMod mod) {
        String declared = mod.metadata().declaredMinecraftVersion().orElse(null);
        String candidate = null;

        if (declared != null && !declared.isBlank()) {
            candidate = firstVersion(declared);
        }

        if (candidate == null) {
            candidate = mod.metadata().minecraftDependency()
                    .filter(dependency -> !dependency.versions().isAny())
                    .map(dependency -> dependency.versions().lowerBound().raw())
                    .map(ModRebuilder::firstVersion)
                    .orElse(null);
        }

        // Plenty of NeoForge and Forge mods name no Minecraft range at all, only
        // the loader's — Jade asks for NeoForge [21.0.143,) — and the loader's
        // version is the game's in disguise.
        if (candidate == null) {
            candidate = fromLoaderDependency(mod);
        }

        // Failing everything, the jar's own name: MouseTweaks-neoforge-mc1.21.
        if (candidate == null) {
            candidate = fromFileName(mod.candidate().path().getFileName().toString());
        }

        if (candidate == null || !candidate.matches("1\\.\\d+(\\.\\d+)?")) {
            return null;
        }

        // 1.21 and 1.21.0 are the same release; Mojang calls it 1.21.
        return candidate.endsWith(".0") && candidate.chars().filter(c -> c == '.').count() == 2
                ? candidate.substring(0, candidate.length() - 2) : candidate;
    }

    /**
     * The release a NeoForge or Forge range implies: NeoForge 21.1.x is 1.21.1
     * (and 21.0.x is 1.21), Forge 47 is 1.20.1.
     */
    static String fromLoaderDependency(LoadedMod mod) {
        for (studios.milkdromeda.octo.mod.ModDependency dependency : mod.metadata().dependencies()) {
            if (dependency.versions().isAny()) {
                continue;
            }

            studios.milkdromeda.octo.mod.version.Version lower = dependency.versions().lowerBound();

            if (lower.size() == 0 || lower.part(0) <= 0) {
                continue;
            }

            switch (dependency.modId()) {
                case "neoforge" -> {
                    // NeoForge's own versions start at 20.2. A lower bound like
                    // [1,) is FML's language loader version, which mods.toml's
                    // loaderVersion also turns into a dependency, and says
                    // nothing about the game.
                    if (lower.part(0) >= 20 && lower.part(0) < 26 && lower.size() >= 2) {
                        return "1." + lower.part(0) + (lower.part(1) > 0 ? "." + lower.part(1) : "");
                    }
                }
                case "forge" -> {
                    String release = studios.milkdromeda.octo.resolve.ModResolver.minecraftForForge(lower.part(0));

                    if (release != null) {
                        return release;
                    }
                }
                default -> {
                }
            }
        }

        return null;
    }

    private static final java.util.regex.Pattern FILE_NAME_RELEASE =
            java.util.regex.Pattern.compile("(?:^|[-_+ ]|mc)(1\\.\\d{1,2}(?:\\.\\d{1,2})?)(?=[-_+ ]|\\.jar$|$)");

    /** {@code -1.21.1-} or {@code mc1.21} in a jar's name. */
    static String fromFileName(String fileName) {
        java.util.regex.Matcher matcher = FILE_NAME_RELEASE.matcher(fileName);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String firstVersion(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?").matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    // --------------------------------------------------------------- rebuild

    private int rebuild(Path source, Path target, Plan plan, LoadedMod mod) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        int classes = 0;

        try (ZipFile zip = new ZipFile(source.toFile())) {
            Map<String, List<String>> mixinTargets = new HashMap<>();
            Map<String, List<String>> parents = parents(zip, mixinTargets);
            // The game's own hierarchy too, for a member the tables list on the
            // class that declares it and a mod calls through a subtype:
            // Registry.get(key), called on BuiltInRegistries.BLOCK, a
            // DefaultedRegistry. Names the game kept are the same on both sides.
            Map<String, List<String>> game = VersionDiff.hierarchy(context.gameJars());
            Remapper remapper = plan.names().remapper(owner -> {
                List<String> mine = parents.get(owner);
                return mine != null ? mine : game.getOrDefault(owner, List.of());
            });
            // A mod in Mojang's names with no refmap names the game in its
            // mixin annotations as plain text, which only this can translate.
            boolean mixinStrings = mod.candidate().namespace() == MappingNamespace.MOJANG && !hasRefmap(zip);
            Remapper renamer = plan.renames().isEmpty() ? null : renamer(plan.renames(), parents, remapper);
            TransformContext transformContext = TransformContext.of(mod, name -> true);
            Set<String> written = new LinkedHashSet<>();

            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temporary))) {
                Enumeration<? extends ZipEntry> entries = zip.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if (entry.isDirectory() || isSignature(name)) {
                        // A signature over the original classes is a lie about the
                        // rewritten ones, and the JVM refuses a jar that lies.
                        continue;
                    }

                    byte[] bytes;

                    try (InputStream in = zip.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }

                    String outName = name;

                    if (name.endsWith(".class") && !name.startsWith("META-INF/versions/")) {
                        byte[] rewritten = rewriteClass(name, mixinStrings
                                ? MixinStrings.translate(bytes, remapper, mixinTargetsOf(bytes)) : bytes,
                                remapper, plan, transformContext);

                        if (renamer != null) {
                            rewritten = rename(rewritten, renamer, mixinStrings);
                        }
                        String internal = new ClassReader(rewritten).getClassName();
                        outName = internal + ".class";
                        bytes = rewritten;
                        classes++;
                    } else if (isNameBearingText(name)) {
                        bytes = rewriteText(name, bytes, plan.names(), remapper);

                        if (renamer != null) {
                            bytes = renameText(name, bytes, plan.renames(), renamer, targetsIn(mixinTargets, remapper));
                        }
                    } else if (name.equals("META-INF/MANIFEST.MF")) {
                        bytes = stripDigests(bytes);
                    }

                    if (!written.add(outName)) {
                        continue;
                    }

                    out.putNextEntry(new ZipEntry(outName));
                    out.write(bytes);
                    out.closeEntry();
                }

                out.putNextEntry(new ZipEntry("META-INF/octo-rebuilt.properties"));
                out.write(("# Rebuilt by Octo Loader for Minecraft " + context.minecraftVersion() + "\n"
                        + "source=" + source.getFileName() + "\n"
                        + "mod=" + mod.id() + "\n"
                        + "steps=" + plan.description() + "\n"
                        + "recipe=" + plan.recipe() + "\n").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return classes;
    }

    /**
     * The second pass's remapper. Its names are the first pass's output, so
     * the mod's own hierarchy is translated into them; the game's comes from
     * the game's jars, for a renamed member a mod reaches through a subclass.
     */
    private Remapper renamer(MappingSet renames, Map<String, List<String>> parents, Remapper first) {
        Map<String, List<String>> game = VersionDiff.hierarchy(context.gameJars());
        Map<String, List<String>> own = new java.util.HashMap<>();

        parents.forEach((owner, list) -> {
            List<String> mapped = new ArrayList<>(list.size());
            list.forEach(parent -> mapped.add(first.map(parent)));
            own.put(first.map(owner), mapped);
        });

        return renames.remapper(owner -> {
            List<String> mine = own.get(owner);
            return mine != null ? mine : game.getOrDefault(owner, List.of());
        });
    }

    private static byte[] rename(byte[] bytes, Remapper renamer, boolean mixinStrings) {
        byte[] current = mixinStrings ? MixinStrings.translate(bytes, renamer, mixinTargetsOf(bytes)) : bytes;
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(current).accept(new ClassRemapper(writer, renamer), 0);
        return writer.toByteArray();
    }

    private static final java.util.regex.Pattern QUALIFIED_MEMBER = java.util.regex.Pattern.compile(
            "L[A-Za-z0-9_/$]+;[A-Za-z0-9_$]+(?:\\([^)\"]*\\)[^\"\\s,]*|:[^\"\\s,]+)");

    /** Each mixin's targets in the first pass's names, which are the names the second pass takes. */
    private static Map<String, List<String>> targetsIn(Map<String, List<String>> targets, Remapper first) {
        Map<String, List<String>> out = new HashMap<>();
        targets.forEach((mixin, list) -> {
            List<String> mapped = new ArrayList<>(list.size());
            list.forEach(target -> mapped.add(first.map(target)));
            out.put(mixin, mapped);
        });
        return out;
    }

    /**
     * The second pass over a refmap, widener or transformer.
     *
     * @param mixinTargets each mixin's targets, which a refmap's unqualified
     *                     entries — an accessor's {@code cookingProgress:I} —
     *                     are members of
     */
    static byte[] renameText(String name, byte[] bytes, MappingSet renames, Remapper renamer,
            Map<String, List<String>> mixinTargets) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String lower = name.toLowerCase(Locale.ROOT);
        String rewritten;

        if (lower.endsWith(".accesswidener") || lower.endsWith(".classtweaker")) {
            rewritten = rewriteAccessWidener(text, renames, renamer);
        } else if (lower.endsWith("accesstransformer.cfg")) {
            rewritten = rewriteAccessTransformer(text, renames, renamer);
        } else {
            String structured = lower.endsWith(".json") ? renameRefmap(text, renamer, mixinTargets) : null;

            if (structured != null) {
                rewritten = structured;
            } else {
                java.util.regex.Matcher matcher = QUALIFIED_MEMBER.matcher(text);
                StringBuilder out = new StringBuilder(text.length());

                while (matcher.find()) {
                    String mapped = MixinStrings.qualifiedReference(matcher.group(), renamer);
                    matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                            mapped != null ? mapped : matcher.group()));
                }

                matcher.appendTail(out);
                rewritten = out.toString();
            }
        }

        return rewritten.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Walks a refmap as what it is — mixin class to annotation string to
     * resolved name — so an entry with no owner is resolved against that
     * mixin's targets.
     *
     * @return the rewritten refmap, or {@code null} when it is not one
     */
    private static String renameRefmap(String text, Remapper renamer, Map<String, List<String>> mixinTargets) {
        com.google.gson.JsonObject root;

        try {
            root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }

        boolean changed = false;
        List<com.google.gson.JsonObject> tables = new ArrayList<>();

        if (root.get("mappings") instanceof com.google.gson.JsonObject mappings) {
            tables.add(mappings);
        }

        if (root.get("data") instanceof com.google.gson.JsonObject data) {
            data.entrySet().forEach(entry -> {
                if (entry.getValue() instanceof com.google.gson.JsonObject table) {
                    tables.add(table);
                }
            });
        }

        for (com.google.gson.JsonObject table : tables) {
            for (Map.Entry<String, com.google.gson.JsonElement> mixin : table.entrySet()) {
                if (!(mixin.getValue() instanceof com.google.gson.JsonObject entries)) {
                    continue;
                }

                List<String> targets = mixinTargets.getOrDefault(mixin.getKey(), List.of());

                for (Map.Entry<String, com.google.gson.JsonElement> entry : entries.entrySet()) {
                    if (!entry.getValue().isJsonPrimitive()) {
                        continue;
                    }

                    String value = entry.getValue().getAsString();
                    String mapped = value.startsWith("L") && value.indexOf(';') > 0
                            ? MixinStrings.qualifiedReference(value, renamer)
                            : unqualified(value, renamer, targets);

                    if (mapped != null) {
                        entry.setValue(new com.google.gson.JsonPrimitive(mapped));
                        changed = true;
                    }
                }
            }
        }

        return changed ? new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root) : text;
    }

    /** {@code name:desc} or {@code name(desc)ret} on one of the mixin's targets. */
    private static String unqualified(String value, Remapper renamer, List<String> targets) {
        int colon = value.indexOf(':');
        int open = value.indexOf('(');

        for (String target : targets) {
            if (open > 0) {
                String name = value.substring(0, open);
                String mapped = name.startsWith("<") ? name : renamer.mapMethodName(target, name, value.substring(open));

                if (!mapped.equals(name)) {
                    return mapped + value.substring(open);
                }
            } else if (colon > 0) {
                String name = value.substring(0, colon);
                String mapped = renamer.mapFieldName(target, name, value.substring(colon + 1));

                if (!mapped.equals(name)) {
                    return mapped + value.substring(colon);
                }
            }
        }

        return null;
    }

    private static boolean hasRefmap(ZipFile zip) {
        return zip.stream().anyMatch(entry -> entry.getName().endsWith("refmap.json"));
    }

    private static List<String> mixinTargetsOf(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return mixinTargets(node);
    }

    private static boolean isSignature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }

    private byte[] rewriteClass(String name, byte[] bytes, Remapper remapper, Plan plan, TransformContext context) {
        byte[] current = bytes;

        if (!plan.names().isEmpty()) {
            ClassReader reader = new ClassReader(current);
            // Frames are copied and remapped rather than recomputed: computing
            // them needs every referenced class loaded, and this runs before the
            // game exists.
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassRemapper(writer, remapper), 0);
            current = writer.toByteArray();
        }

        if (!plan.rules().isEmpty()) {
            String internal = new ClassReader(current).getClassName();
            current = plan.rules().apply(internal, current, context);
        }

        return current;
    }

    /**
     * Every class's parents, and every mixin's targets, as the source jar names
     * them, so a member called through a subclass or declared in a mixin is
     * looked up on the class that actually has it.
     */
    private static Map<String, List<String>> parents(ZipFile zip) throws IOException {
        return parents(zip, new HashMap<>());
    }

    /** @param mixinTargetsOut filled with each mixin's own targets, which a refmap's entries are relative to */
    private static Map<String, List<String>> parents(ZipFile zip, Map<String, List<String>> mixinTargetsOut)
            throws IOException {
        Map<String, List<String>> out = new HashMap<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();

        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();

            if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) {
                continue;
            }

            byte[] bytes;

            try (InputStream in = zip.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }

            try {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                        | ClassReader.SKIP_FRAMES);
                List<String> list = new ArrayList<>();

                if (node.superName != null) {
                    list.add(node.superName);
                }

                list.addAll(node.interfaces);
                List<String> targets = mixinTargets(node);
                list.addAll(targets);
                out.put(node.name, list);

                if (!targets.isEmpty()) {
                    mixinTargetsOut.put(node.name, targets);
                }
            } catch (RuntimeException e) {
                LOG.debug("could not read {}: {}", entry.getName(), e.toString());
            }
        }

        return out;
    }

    private static List<String> mixinTargets(ClassNode node) {
        List<String> out = new ArrayList<>();

        for (List<AnnotationNode> annotations : java.util.Arrays.asList(node.invisibleAnnotations,
                node.visibleAnnotations)) {
            if (annotations == null) {
                continue;
            }

            for (AnnotationNode annotation : annotations) {
                if (!MIXIN.equals(annotation.desc) || annotation.values == null) {
                    continue;
                }

                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    Object key = annotation.values.get(i);
                    Object value = annotation.values.get(i + 1);

                    if (value instanceof List<?> list) {
                        for (Object item : list) {
                            if ("value".equals(key) && item instanceof Type type) {
                                out.add(type.getInternalName());
                            } else if ("targets".equals(key) && item instanceof String text) {
                                out.add(text.replace('.', '/'));
                            }
                        }
                    }
                }
            }
        }

        return out;
    }

    // ------------------------------------------------------------------ text

    private static boolean isNameBearingText(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return (lower.endsWith(".json") && lower.contains("refmap"))
                || lower.endsWith(".accesswidener") || lower.endsWith(".classtweaker")
                || lower.endsWith("accesstransformer.cfg");
    }

    private static byte[] rewriteText(String name, byte[] bytes, MappingSet names, Remapper remapper) {
        if (names.isEmpty()) {
            return bytes;
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        String lower = name.toLowerCase(Locale.ROOT);
        String rewritten;

        if (lower.endsWith(".accesswidener") || lower.endsWith(".classtweaker")) {
            rewritten = rewriteAccessWidener(text, names, remapper);
        } else if (lower.endsWith("accesstransformer.cfg")) {
            rewritten = rewriteAccessTransformer(text, names, remapper);
        } else {
            rewritten = names.mapString(text);
        }

        return rewritten.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@code accessible method net/minecraft/class_1 method_2 (Lnet/minecraft/class_3;)V}
     * and friends. The header's namespace is moved to {@code named}, which is
     * what Mojang's names are called in a widener.
     */
    static String rewriteAccessWidener(String text, MappingSet names, Remapper remapper) {
        StringBuilder out = new StringBuilder(text.length());

        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();

            if (trimmed.startsWith("accessWidener") || trimmed.startsWith("classTweaker")) {
                String[] header = trimmed.split("\\s+");

                if (header.length >= 3) {
                    header[2] = "named";
                    out.append(String.join("\t", header)).append('\n');
                    continue;
                }
            }

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.append(line).append('\n');
                continue;
            }

            String comment = "";
            int hash = trimmed.indexOf('#');

            if (hash >= 0) {
                comment = " " + trimmed.substring(hash);
                trimmed = trimmed.substring(0, hash).strip();
            }

            String[] parts = trimmed.split("\\s+");

            if (parts.length >= 3 && parts[1].equals("class")) {
                parts[2] = names.mapClass(parts[2]);
            } else if (parts.length >= 5 && parts[1].equals("method")) {
                String owner = parts[2];
                parts[3] = remapper.mapMethodName(owner, parts[3], parts[4]);
                parts[2] = names.mapClass(owner);
                parts[4] = remapper.mapMethodDesc(parts[4]);
            } else if (parts.length >= 5 && parts[1].equals("field")) {
                String owner = parts[2];
                parts[3] = remapper.mapFieldName(owner, parts[3], parts[4]);
                parts[2] = names.mapClass(owner);
                parts[4] = remapper.mapDesc(parts[4]);
            } else if (parts.length >= 3 && parts[0].startsWith("inject-interface")) {
                parts[1] = names.mapClass(parts[1]);
                parts[2] = names.mapClass(parts[2]);
            }

            out.append(String.join("\t", parts)).append(comment).append('\n');
        }

        // split(-1) keeps a final empty element, which the loop turned into one
        // newline too many.
        return out.length() > 0 ? out.substring(0, out.length() - 1) : "";
    }

    /** {@code public net.minecraft.world.entity.Entity m_20182_()Lnet/minecraft/world/phys/Vec3; # comment} */
    static String rewriteAccessTransformer(String text, MappingSet names, Remapper remapper) {
        StringBuilder out = new StringBuilder(text.length());

        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.append(line).append('\n');
                continue;
            }

            String comment = "";
            int hash = trimmed.indexOf('#');

            if (hash >= 0) {
                comment = " " + trimmed.substring(hash);
                trimmed = trimmed.substring(0, hash).strip();
            }

            String[] parts = trimmed.split("\\s+");

            if (parts.length >= 2) {
                String owner = parts[1].replace('.', '/');
                parts[1] = names.mapClass(owner).replace('/', '.');

                if (parts.length >= 3) {
                    String member = parts[2];
                    int open = member.indexOf('(');

                    if (open > 0) {
                        String descriptor = member.substring(open);
                        parts[2] = remapper.mapMethodName(owner, member.substring(0, open), descriptor)
                                + remapper.mapMethodDesc(descriptor);
                    } else if (!member.equals("*") && !member.equals("*()")) {
                        parts[2] = remapper.mapFieldName(owner, member, null);
                    }
                }
            }

            out.append(String.join(" ", parts)).append(comment).append('\n');
        }

        return out.length() > 0 ? out.substring(0, out.length() - 1) : "";
    }

    /** A manifest without the per-file digests a signature would have checked. */
    private static byte[] stripDigests(byte[] manifest) {
        String text = new String(manifest, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int firstSection = text.indexOf("\n\n");
        String main = firstSection < 0 ? text : text.substring(0, firstSection + 1);
        return (main.endsWith("\n") ? main : main + "\n").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------------- cache

    private static String cacheKey(Path source, String recipe) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream in = Files.newInputStream(source)) {
                byte[] buffer = new byte[1 << 16];

                for (int read; (read = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, read);
                }
            }

            digest.update(recipe.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String safe(String text) {
        String cleaned = text.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
