package studios.milkdromeda.octoloader.discovery;

import studios.milkdromeda.octoloader.detect.DetectedMod;
import studios.milkdromeda.octoloader.detect.ModDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Walks a mods directory and identifies every jar in it.
 */
public final class ModScanner {
    private final ModDetector detector = new ModDetector();

    public List<DetectedMod> scan(Path modsDir) {
        if (!Files.isDirectory(modsDir)) return List.of();
        List<DetectedMod> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(modsDir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> results.add(detector.detect(p)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan mods directory " + modsDir, e);
        }
        return results;
    }
}
