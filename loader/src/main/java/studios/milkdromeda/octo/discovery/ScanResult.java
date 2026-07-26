package studios.milkdromeda.octo.discovery;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import studios.milkdromeda.octo.compat.Era.MappingNamespace;

/** What a pass over a mod's bytecode revealed. */
public final class ScanResult {
    /** Classes carrying {@code @Mod}, mapped to the mod id they declare (may be empty). */
    private final Map<String, String> modAnnotations = new LinkedHashMap<>();
    private final Set<String> fabricInitializers = new LinkedHashSet<>();
    private final Set<String> clientInitializers = new LinkedHashSet<>();
    private final Set<String> serverInitializers = new LinkedHashSet<>();
    private final Set<String> preLaunchInitializers = new LinkedHashSet<>();
    private final Set<String> liteMods = new LinkedHashSet<>();
    private final Set<String> modLoaderClasses = new LinkedHashSet<>();
    private final Set<String> referencedGameClasses = new LinkedHashSet<>();
    private final Map<MappingNamespace, Integer> namespaceHits = new LinkedHashMap<>();

    private int maxClassFileVersion;
    private int classCount;

    public Map<String, String> modAnnotations() {
        return modAnnotations;
    }

    public Set<String> fabricInitializers() {
        return fabricInitializers;
    }

    public Set<String> clientInitializers() {
        return clientInitializers;
    }

    public Set<String> serverInitializers() {
        return serverInitializers;
    }

    public Set<String> preLaunchInitializers() {
        return preLaunchInitializers;
    }

    public Set<String> liteMods() {
        return liteMods;
    }

    /** {@code mod_*} / {@code BaseMod} subclasses from the ModLoader era. */
    public Set<String> modLoaderClasses() {
        return modLoaderClasses;
    }

    public Set<String> referencedGameClasses() {
        return referencedGameClasses;
    }

    public int maxClassFileVersion() {
        return maxClassFileVersion;
    }

    public int classCount() {
        return classCount;
    }

    public boolean isEmpty() {
        return modAnnotations.isEmpty() && fabricInitializers.isEmpty() && clientInitializers.isEmpty()
                && serverInitializers.isEmpty() && preLaunchInitializers.isEmpty()
                && liteMods.isEmpty() && modLoaderClasses.isEmpty();
    }

    void countClass(int classFileVersion) {
        classCount++;
        maxClassFileVersion = Math.max(maxClassFileVersion, classFileVersion);
    }

    void hit(MappingNamespace namespace) {
        namespaceHits.merge(namespace, 1, Integer::sum);
    }

    /** The naming scheme the mod's references to the game are written in. */
    public MappingNamespace dominantNamespace() {
        MappingNamespace best = MappingNamespace.UNKNOWN;
        int bestHits = 0;

        for (Map.Entry<MappingNamespace, Integer> entry : namespaceHits.entrySet()) {
            if (entry.getValue() > bestHits) {
                best = entry.getKey();
                bestHits = entry.getValue();
            }
        }

        return best;
    }

    public int namespaceHits(MappingNamespace namespace) {
        return namespaceHits.getOrDefault(namespace, 0);
    }

    /** The Java version the mod was compiled for, e.g. {@code 8} or {@code 17}. */
    public int javaVersion() {
        return maxClassFileVersion == 0 ? 0 : maxClassFileVersion - 44;
    }
}
