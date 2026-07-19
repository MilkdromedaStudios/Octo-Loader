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
import studios.milkdromeda.octoloader.detect.DetectedMod;
import studios.milkdromeda.octoloader.detect.ModEcosystem;
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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Translates same-version NeoForge mods into Fabric mods.
 *
 * <p>The 26.x era makes the bytecode side easy: both NeoForge and Fabric run
 * on Minecraft's real (unobfuscated) class names, so no remapping is needed.
 * What remains is the loader API surface. Octo ships shims for a starter
 * subset (the {@code @Mod} entry contract, mod event bus, lifecycle events);
 * every NeoForge/Forge API reference outside that subset flags the mod as
 * {@link CompatStatus#PARTIAL} with the exact classes named, and the mod is
 * skipped rather than half-loaded.
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

        try {
            ScanResult scan = scanClasses(mod.path());
            if (!scan.uncoveredApis().isEmpty()) {
                return new ModReportEntry(mod, CompatStatus.PARTIAL,
                        "uses NeoForge APIs Octo Loader does not provide yet: " + summarize(scan.uncoveredApis()));
            }

            if (TranslationCache.isFresh(mod.path(), output)) {
                return translatedEntry(mod, context, "already translated (cache)");
            }

            JsonObject fabric = toFabricMetadata(mod, scan.entryClasses());
            JarRepacker.writeTranslated(mod.path(), output, GSON.toJson(fabric), id());
            String note = scan.entryClasses().isEmpty() ? "library jar (no @Mod entry class)" : null;
            return translatedEntry(mod, context, note);
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

    private record ScanResult(List<String> entryClasses, Set<String> uncoveredApis) {
    }

    private static ScanResult scanClasses(Path jar) throws IOException {
        List<String> entryClasses = new ArrayList<>();
        Set<String> uncovered = new TreeSet<>();

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
                for (String ref : ConstantPool.referencedClasses(new java.io.ByteArrayInputStream(bytes))) {
                    if (isForeignApi(ref) && !COVERED_APIS.contains(ref)) {
                        uncovered.add(ref.replace('/', '.'));
                    }
                }
                String modAnnotated = findModAnnotation(bytes);
                if (modAnnotated != null) {
                    entryClasses.add(modAnnotated);
                }
            }
        }
        return new ScanResult(entryClasses, uncovered);
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

    private static String summarize(Set<String> classes) {
        List<String> list = new ArrayList<>(classes);
        if (list.size() <= 4) return String.join(", ", list);
        return String.join(", ", list.subList(0, 4)) + " (+" + (list.size() - 4) + " more)";
    }

    // ----------------------------------------------------------------- metadata

    private JsonObject toFabricMetadata(DetectedMod mod, List<String> entryClasses) throws IOException {
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
