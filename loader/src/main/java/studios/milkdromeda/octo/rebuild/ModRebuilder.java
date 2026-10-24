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
    private static final int FORMAT = 2;

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
    private record Plan(MappingSet names, TransformPipeline rules, String recipe, String description) {
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
        return Optional.of(new Plan(names, rules, String.join(";", recipe), String.join(", ", steps)));
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
            Map<String, List<String>> parents = parents(zip);
            Remapper remapper = plan.names().remapper(owner -> parents.getOrDefault(owner, List.of()));
            // A mod in Mojang's names with no refmap names the game in its
            // mixin annotations as plain text, which only this can translate.
            boolean mixinStrings = mod.candidate().namespace() == MappingNamespace.MOJANG && !hasRefmap(zip);
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
                        String internal = new ClassReader(rewritten).getClassName();
                        outName = internal + ".class";
                        bytes = rewritten;
                        classes++;
                    } else if (isNameBearingText(name)) {
                        bytes = rewriteText(name, bytes, plan.names(), remapper);
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
                list.addAll(mixinTargets(node));
                out.put(node.name, list);
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
