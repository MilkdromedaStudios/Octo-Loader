package studios.milkdromeda.octoloader.translate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Rewrites a foreign mod jar as a Fabric mod jar: original contents preserved,
 * a generated {@code fabric.mod.json} added, and provenance attributes stamped
 * into the manifest so the output is recognizable and cache-checkable.
 */
public final class JarRepacker {
    private JarRepacker() {
    }

    public static void writeTranslated(Path source, Path output, String fabricModJson, String translatorId)
            throws IOException {
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
            main.put(TranslationCache.ATTR_TRANSLATOR, translatorId);

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
                            entryIn.transferTo(out);
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
