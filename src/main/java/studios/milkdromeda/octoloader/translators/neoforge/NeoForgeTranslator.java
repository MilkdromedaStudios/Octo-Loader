package studios.milkdromeda.octoloader.translators.neoforge;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import studios.milkdromeda.octoloader.bytecode.ConstantPool;
import studios.milkdromeda.octoloader.detect.DetectedMod;
import studios.milkdromeda.octoloader.detect.ModEcosystem;
import studios.milkdromeda.octoloader.replace.ApiReplacement;
import studios.milkdromeda.octoloader.replace.ApiRewriter;
import studios.milkdromeda.octoloader.replace.ChainRemapper;
import studios.milkdromeda.octoloader.replace.ReplacementPlan;
import studios.milkdromeda.octoloader.report.CompatStatus;
import studios.milkdromeda.octoloader.report.ModReportEntry;
import studios.milkdromeda.octoloader.translate.JarRepacker;
import studios.milkdromeda.octoloader.translate.TranslationCache;
import studios.milkdromeda.octoloader.translate.TranslationContext;
import studios.milkdromeda.octoloader.translate.Translator;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Translates NeoForge mods into Fabric mods.
 *
 * <p>The 26.x era makes the bytecode side easy: both NeoForge and Fabric run
 * on Minecraft's real (unobfuscated) class names, so no remapping is needed
 * for same-version mods. Mods built for an older 26.x version go through the
 * API replacement chain first: the documented per-version changes are composed
 * and applied to the mod's compiled references, so a 26.1 mod loads on 26.2.
 *
 * <p>What remains is the loader API surface. Octo ships shims for a starter
 * subset (the {@code @Mod} entry contract, mod event bus, lifecycle events);
 * every NeoForge/Forge API reference outside that subset — after replacement —
 * flags the mod as {@link CompatStatus#PARTIAL} with the exact classes named,
 * and the mod is skipped rather than half-loaded.
 */
public final class NeoForgeTranslator implements Translator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String MOD_ANNOTATION_DESC = "Lnet/neoforged/fml/common/Mod;";

    /** Internal names of the NeoForge API classes Octo's shims currently provide. */
    private static final Set<String> COVERED_APIS = Set.of(
            "net/neoforged/fml/common/Mod",
            "net/neoforged/fml/common/Mod$Dist",
            "net/neoforged/bus/api/Event",
            "net/neoforged/bus/api/IEventBus",
            "net/neoforged/bus/api/SubscribeEvent",
            "net/neoforged/fml/event/lifecycle/FMLCommonSetupEvent",
            "net/neoforged/fml/event/lifecycle/FMLClientSetupEvent",
            "net/neoforged/fml/event/lifecycle/FMLDedicatedServerSetupEvent",
            "net/neoforged/fml/ModContainer"
    );

    private static final List<String> FOREIGN_PREFIXES =
            List.of("net/neoforged/", "net/minecraftforge/", "cpw/mods/");

    @Override
    public String id() {
        return "neoforge";
    }

    @Override
    public boolean supports(DetectedMod mod) {
        return mod.ecosystem() == ModEcosystem.NEOFORGE;
    }

    @Override
    public ModReportEntry translate(DetectedMod mod, TranslationContext context) {
        Path output = TranslationCache.outputFor(mod.path(), context.injector().outputDir());

        ReplacementPlan plan = ApiReplacement.plan(mod, context);
        if (plan instanceof ReplacementPlan.Blocked blocked) {
            return new ModReportEntry(mod, CompatStatus.UNSUPPORTED_VERSION, blocked.reason());
        }
        ReplacementPlan.Replace replace = plan instanceof ReplacementPlan.Replace r ? r : null;

        try {
            ScanResult scan = scanClasses(mod.path());

            if (replace != null) {
                List<String> removed = replace.removalBlockers(scan.refsByEntry().values());
                if (!removed.isEmpty()) {
                    return new ModReportEntry(mod, CompatStatus.PARTIAL,
                            "uses APIs the documentation records as removed between " + replace.fromVersion()
                                    + " and " + replace.toVersion() + ": " + summarize(removed));
                }
            }

            Set<String> uncovered = uncoveredApis(scan, replace);
            if (!uncovered.isEmpty()) {
                return new ModReportEntry(mod, CompatStatus.PARTIAL,
                        "uses NeoForge APIs Octo Loader does not provide yet: " + summarize(uncovered));
            }

            if (TranslationCache.isFresh(mod.path(), output, context.runningMinecraft())) {
                return translatedEntry(mod, context, "already translated (cache)");
            }

            JsonObject fabric = toFabricMetadata(mod, scan.entryClasses(), replace);
            JarRepacker.ProvenanceStamp stamp = new JarRepacker.ProvenanceStamp(
                    id(), context.runningMinecraft(),
                    replace != null ? replace.fromVersion() + " -> " + replace.toVersion() : null);
            JarRepacker.writeTranslated(mod.path(), output, GSON.toJson(fabric), stamp,
                    classTransform(scan, replace));

            List<String> notes = new ArrayList<>();
            if (replace != null) {
                notes.add("APIs replaced " + replace.description()
                        + ", " + countTouched(scan, replace) + " class(es) rewritten");
            }
            if (scan.entryClasses().isEmpty()) {
                notes.add("library jar (no @Mod entry class)");
            }
            return translatedEntry(mod, context, notes.isEmpty() ? null : String.join("; ", notes));
        } catch (IOException e) {
            throw new RuntimeException("I/O error translating " + mod.path().getFileName(), e);
        }
    }

    private ModReportEntry translatedEntry(DetectedMod mod, TranslationContext context, String prefix) {
        String state = context.loadedModCheck().test(mod.modId())
                ? "active"
                : context.injector().activationNote();
        return new ModReportEntry(mod, CompatStatus.TRANSLATED,
                prefix == null ? state : prefix + "; " + state);
    }

    // ----------------------------------------------------------------- scanning

    private record ScanResult(List<String> entryClasses, Map<String, ConstantPool.ClassRefs> refsByEntry) {
    }

    private static ScanResult scanClasses(Path jar) throws IOException {
        List<String> entryClasses = new ArrayList<>();
        Map<String, ConstantPool.ClassRefs> refsByEntry = new LinkedHashMap<>();

        try (JarFile jf = new JarFile(jar.toFile())) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")
                        || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = jf.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                refsByEntry.put(entry.getName(), ConstantPool.scan(bytes));
                String modAnnotated = findModAnnotation(bytes);
                if (modAnnotated != null) {
                    entryClasses.add(modAnnotated);
                }
            }
        }
        return new ScanResult(entryClasses, refsByEntry);
    }

    /**
     * Loader API references outside the shimmed subset. References are carried
     * through the replacement chain first, so a 26.1-era name whose documented
     * 26.2 equivalent is shimmed counts as covered.
     */
    private static Set<String> uncoveredApis(ScanResult scan, ReplacementPlan.Replace replace) {
        ChainRemapper remapper = replace != null ? replace.remapper() : null;
        Set<String> uncovered = new TreeSet<>();
        for (ConstantPool.ClassRefs refs : scan.refsByEntry().values()) {
            for (String ref : refs.classes()) {
                String mapped = remapper != null ? remapper.map(ref) : ref;
                if (isForeignApi(mapped) && !COVERED_APIS.contains(mapped)) {
                    uncovered.add(mapped.replace('/', '.'));
                }
            }
        }
        return uncovered;
    }

    private static java.util.function.BiFunction<String, byte[], byte[]> classTransform(
            ScanResult scan, ReplacementPlan.Replace replace) {
        if (replace == null) return null;
        ChainRemapper remapper = replace.remapper();
        return (entryName, bytes) -> {
            ConstantPool.ClassRefs refs = scan.refsByEntry().get(entryName);
            if (refs == null || !replace.touches(refs)) return bytes;
            return ApiRewriter.rewrite(bytes, remapper);
        };
    }

    private static long countTouched(ScanResult scan, ReplacementPlan.Replace replace) {
        return scan.refsByEntry().values().stream().filter(replace::touches).count();
    }

    private static boolean isForeignApi(String internalName) {
        for (String prefix : FOREIGN_PREFIXES) {
            if (internalName.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Returns the class name if the class carries {@code @Mod}, else null. */
    private static String findModAnnotation(byte[] classBytes) {
        var result = new Object() {
            String className;
            boolean annotated;
        };
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                result.className = name.replace('/', '.');
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (MOD_ANNOTATION_DESC.equals(descriptor)) {
                    result.annotated = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result.annotated ? result.className : null;
    }

    private static String summarize(java.util.Collection<String> items) {
        List<String> list = new ArrayList<>(items);
        if (list.size() <= 4) return String.join(", ", list);
        return String.join(", ", list.subList(0, 4)) + " (+" + (list.size() - 4) + " more)";
    }

    // ----------------------------------------------------------------- metadata

    private JsonObject toFabricMetadata(DetectedMod mod, List<String> entryClasses,
                                        ReplacementPlan.Replace replace) throws IOException {
        Config toml = readModsToml(mod.path());
        Config firstMod = null;
        List<Config> mods = toml != null ? toml.get("mods") : null;
        if (mods != null && !mods.isEmpty()) firstMod = mods.getFirst();

        JsonObject fabric = new JsonObject();
        fabric.addProperty("schemaVersion", 1);
        fabric.addProperty("id", mod.modId());
        fabric.addProperty("version", mod.version() != null ? mod.version() : "0.0.0");
        if (mod.displayName() != null) fabric.addProperty("name", mod.displayName());
        if (firstMod != null) {
            String description = firstMod.get("description");
            if (description != null) fabric.addProperty("description", description.strip());
            String authors = firstMod.get("authors");
            if (authors != null) {
                JsonArray array = new JsonArray();
                array.add(authors);
                fabric.add("authors", array);
            }
        }
        if (toml != null) {
            String license = toml.get("license");
            if (license != null) fabric.addProperty("license", license);
        }

        JsonObject depends = new JsonObject();
        depends.addProperty("fabricloader", ">=0.19.0");
        fabric.add("depends", depends);

        JsonObject neoforge = new JsonObject();
        JsonArray entries = new JsonArray();
        for (String entryClass : entryClasses) entries.add(entryClass);
        neoforge.add("entryClasses", entries);

        JsonObject octo = new JsonObject();
        octo.addProperty("translatedFrom", "neoforge");
        if (replace != null) {
            octo.addProperty("apiReplaced", replace.fromVersion() + " -> " + replace.toVersion());
        }
        octo.add("neoforge", neoforge);
        JsonObject custom = new JsonObject();
        custom.add("octoloader", octo);
        fabric.add("custom", custom);
        return fabric;
    }

    private static Config readModsToml(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry entry = jf.getJarEntry("META-INF/neoforge.mods.toml");
            if (entry == null) return null;
            try (InputStream in = jf.getInputStream(entry)) {
                return new TomlParser().parse(new StringReader(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
    }
}
