package studios.milkdromeda.octoloader.translate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BiFunction;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Rewrites a foreign mod jar as a Fabric mod jar: original contents preserved
 * (optionally with class files transformed by the API replacement chain), a
 * generated {@code fabric.mod.json} added, and provenance attributes stamped
 * into the manifest so the output is recognizable and cache-checkable.
 */
public final class JarRepacker {
    private JarRepacker() {
    }

    /**
     * Provenance stamped into the output manifest.
     *
     * @param translatorId     which translator produced the jar
     * @param runningMinecraft the Minecraft version the output was produced for
     * @param apiReplaced      {@code "from → to"} when the API replacement chain
     *                         ran, or {@code null} for same-version output
     */
    public record ProvenanceStamp(String translatorId, String runningMinecraft, String apiReplaced) {
    }

    public static void writeTranslated(Path source, Path output, String fabricModJson, ProvenanceStamp stamp,
                                       BiFunction<String, byte[], byte[]> classTransform) throws IOException {
        Files.createDirectories(output.getParent());
        Path tmp = output.resolveSibling(output.getFileName() + ".tmp");

        try (JarFile in = new JarFile(source.toFile())) {
            Manifest manifest = in.getManifest() != null ? new Manifest(in.getManifest()) : new Manifest();
            Attributes main = manifest.getMainAttributes();
            if (!main.containsKey(Attributes.Name.MANIFEST_VERSION)) {
                main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            }
            main.put(TranslationCache.ATTR_TRANSLATED_FROM, source.getFileName().toString());
            main.put(TranslationCache.ATTR_SOURCE_SHA256, TranslationCache.sha256(source));
            main.put(TranslationCache.ATTR_TRANSLATOR, stamp.translatorId());
            main.put(TranslationCache.ATTR_RUNNING_MC, stamp.runningMinecraft());
            if (stamp.apiReplaced() != null) {
                main.put(TranslationCache.ATTR_API_REPLACED, stamp.apiReplaced());
            }

            try (OutputStream fileOut = Files.newOutputStream(tmp);
                 JarOutputStream out = new JarOutputStream(fileOut, manifest)) {
                var entries = in.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.equals("META-INF/MANIFEST.MF") || name.equals("fabric.mod.json")) continue;
                    out.putNextEntry(new JarEntry(name));
                    if (!entry.isDirectory()) {
                        try (InputStream entryIn = in.getInputStream(entry)) {
                            if (classTransform != null && name.endsWith(".class") && !name.startsWith("META-INF/")) {
                                out.write(classTransform.apply(name, entryIn.readAllBytes()));
                            } else {
                                entryIn.transferTo(out);
                            }
                        }
                    }
                    out.closeEntry();
                }
                out.putNextEntry(new JarEntry("fabric.mod.json"));
                out.write(fabricModJson.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
    }
}
