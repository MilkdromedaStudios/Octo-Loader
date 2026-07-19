package studios.milkdromeda.octoloader.detect;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of inspecting a single jar (or nested jar): which ecosystem it belongs
 * to, what it identifies itself as, and which Minecraft it targets.
 *
 * @param path            jar location on disk
 * @param ecosystem       detected ecosystem (highest-priority marker)
 * @param allMarkers      every ecosystem whose marker file was present
 * @param modId           declared mod id, or {@code null} if unparseable
 * @param displayName     human-readable name, falls back to modId/file name
 * @param version         declared version, or {@code null}
 * @param targetMinecraft declared Minecraft version or range, or {@code null} when undeclared
 * @param loaderVersion   declared loader version requirement, or {@code null}
 * @param nestedJars      paths (inside the jar) of bundled nested mod jars
 * @param detail          free-form notes gathered during detection
 */
public record DetectedMod(
        Path path,
        ModEcosystem ecosystem,
        List<ModEcosystem> allMarkers,
        String modId,
        String displayName,
        String version,
        String targetMinecraft,
        String loaderVersion,
        List<String> nestedJars,
        String detail
) {
    public String bestName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (modId != null && !modId.isBlank()) return modId;
        return path.getFileName().toString();
    }
}
