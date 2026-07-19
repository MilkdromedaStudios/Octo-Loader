package studios.milkdromeda.octoloader.translators.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studios.milkdromeda.octoloader.TestCompiler;
import studios.milkdromeda.octoloader.bootstrap.ModInjector;
import studios.milkdromeda.octoloader.detect.ModDetector;
import studios.milkdromeda.octoloader.knowledge.KnowledgeBase;
import studios.milkdromeda.octoloader.report.CompatStatus;
import studios.milkdromeda.octoloader.report.ModReportEntry;
import studios.milkdromeda.octoloader.translate.TranslationContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeTranslatorTest {
    private static final String MODS_TOML = """
            modLoader="javafml"
            loaderVersion="[4,)"
            license="MIT"

            [[mods]]
            modId="fixmod"
            version="1.0.0"
            displayName="Fixture Mod"

            [[dependencies.fixmod]]
            modId="minecraft"
            versionRange="[26.2,)"
            """;

    private final NeoForgeTranslator translator = new NeoForgeTranslator();
    private final ModDetector detector = new ModDetector();

    @TempDir
    Path gameDir;

    private Path buildJar(String className, String source) throws IOException {
        Map<String, byte[]> classes = TestCompiler.compile(gameDir.resolve("work"), className, source);
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Path jar = mods.resolve("fixture-neo.jar");
        try (OutputStream fileOut = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(fileOut)) {
            out.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
            out.write(MODS_TOML.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    private ModReportEntry translate(Path jar) {
        TranslationContext context = TranslationContext.create(gameDir, "26.2", KnowledgeBase.load(),
                ModInjector.modsDir(gameDir.resolve("mods")), id -> false);
        return translator.translate(detector.detect(jar), context);
    }

    @Test
    void translatesModUsingOnlyCoveredApis() throws IOException {
        Path jar = buildJar("fix.FixMod", """
                package fix;

                import net.neoforged.bus.api.IEventBus;
                import net.neoforged.fml.common.Mod;
                import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

                @Mod("fixmod")
                public class FixMod {
                    public FixMod(IEventBus bus) {
                        bus.addListener(FMLCommonSetupEvent.class, e -> System.out.println("setup"));
                    }
                }
                """);

        ModReportEntry entry = translate(jar);
        assertEquals(CompatStatus.TRANSLATED, entry.status(), entry.reason());

        Path output = gameDir.resolve("mods").resolve("fixture-neo.octo.jar");
        assertTrue(Files.isRegularFile(output));
        JsonObject fabric = readFabricMetadata(output);
        assertEquals("fixmod", fabric.get("id").getAsString());
        assertEquals("1.0.0", fabric.get("version").getAsString());
        String entryClass = fabric.getAsJsonObject("custom").getAsJsonObject("octoloader")
                .getAsJsonObject("neoforge").getAsJsonArray("entryClasses").get(0).getAsString();
        assertEquals("fix.FixMod", entryClass);
    }

    @Test
    void flagsUncoveredNeoForgeApisAsPartial() throws IOException {
        Path jar = buildJar("fix.RegMod", """
                package fix;

                import net.neoforged.fml.common.Mod;
                import net.neoforged.neoforge.registries.DeferredRegister;

                @Mod("fixmod")
                public class RegMod {
                    public static final DeferredRegister ITEMS = DeferredRegister.create("item", "fixmod");
                }
                """);

        ModReportEntry entry = translate(jar);
        assertEquals(CompatStatus.PARTIAL, entry.status());
        assertTrue(entry.reason().contains("net.neoforged.neoforge.registries.DeferredRegister"), entry.reason());
        assertTrue(Files.notExists(gameDir.resolve("mods").resolve("fixture-neo.octo.jar")),
                "partial mods must not emit jars");
    }

    @Test
    void translatesLibraryJarWithoutEntryClass() throws IOException {
        Path jar = buildJar("fix.Util", """
                package fix;

                public class Util {
                    public static int add(int a, int b) {
                        return a + b;
                    }
                }
                """);

        ModReportEntry entry = translate(jar);
        assertEquals(CompatStatus.TRANSLATED, entry.status(), entry.reason());
        assertTrue(entry.reason().contains("library"), entry.reason());
    }

    private static JsonObject readFabricMetadata(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile());
             InputStream in = jf.getInputStream(jf.getJarEntry("fabric.mod.json"))) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
