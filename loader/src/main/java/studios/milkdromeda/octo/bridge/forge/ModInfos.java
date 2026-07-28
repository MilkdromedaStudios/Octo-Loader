package studios.milkdromeda.octo.bridge.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * Describes a loaded mod the way Forge and NeoForge describe one.
 *
 * <p>Both ecosystems publish the same shape under different names — Forge's
 * {@code net.minecraftforge.forgespi.language.IModInfo} and NeoForge's
 * {@code net.neoforged.neoforgespi.language.IModInfo} — and a mod compiled
 * against one will not accept the other's, so both are built here from the same
 * single source of truth: Octo's own runtime.
 *
 * <p>The whole point of this class is that the answers are real. A NeoForge mod
 * asking what version {@code sodium} is gets Sodium's actual version, out of
 * Sodium's actual {@code fabric.mod.json}, because under Octo there is one mod
 * list rather than one per ecosystem.
 */
public final class ModInfos {
    private static final Map<String, NeoInfo> NEO = new ConcurrentHashMap<>();
    private static final Map<String, ForgeInfo> FORGE = new ConcurrentHashMap<>();

    private ModInfos() {
    }

    /** @return NeoForge's description of a mod, whichever loader it was built for */
    public static net.neoforged.neoforgespi.language.IModInfo neo(String modId) {
        return NEO.computeIfAbsent(modId, NeoInfo::new);
    }

    /** @return Forge's description of a mod, whichever loader it was built for */
    public static net.minecraftforge.forgespi.language.IModInfo forge(String modId) {
        return FORGE.computeIfAbsent(modId, ForgeInfo::new);
    }

    private static Optional<LoadedMod> mod(String modId) {
        return OctoRuntime.isRunning() ? OctoRuntime.get().mod(modId) : Optional.empty();
    }

    private static String version(String modId) {
        return mod(modId).map(loaded -> loaded.metadata().version().raw()).orElse("0.0.0");
    }

    private static List<ModDependency> dependencies(String modId) {
        return mod(modId).map(loaded -> loaded.metadata().dependencies()).orElse(List.of());
    }

    /**
     * The loader id a mod declared, as Forge names them.
     *
     * <p>A mod asking this about another mod is nearly always deciding how to
     * talk to it, so answering {@code javafml} for a Fabric mod would be a lie
     * with consequences.
     */
    private static String loaderOf(String modId) {
        return mod(modId).map(loaded -> switch (loaded.format()) {
            case FABRIC, RIFT -> "fabric";
            case QUILT -> "quilt";
            case LITELOADER -> "liteloader";
            case FORGE, NEOFORGE, LEGACY_FORGE, BARE -> "javafml";
        }).orElse("javafml");
    }

    private static String displayName(String modId) {
        return mod(modId).map(loaded -> loaded.metadata().name()).orElse(modId);
    }

    private static String description(String modId) {
        return mod(modId).map(loaded -> loaded.metadata().description()).orElse("");
    }

    private static String license(String modId) {
        return mod(modId).map(loaded -> loaded.metadata().license()).orElse("");
    }

    private static String fileName(String modId) {
        return mod(modId).map(loaded -> loaded.effectivePath().getFileName().toString()).orElse(modId);
    }

    // ---------------------------------------------------------------- NeoForge

    private static final class NeoInfo implements net.neoforged.neoforgespi.language.IModInfo {
        private final String modId;

        NeoInfo(String modId) {
            this.modId = modId;
        }

        @Override
        public String getModId() {
            return modId;
        }

        @Override
        public String getNamespace() {
            return modId;
        }

        @Override
        public ArtifactVersion getVersion() {
            return new DefaultArtifactVersion(version(modId));
        }

        @Override
        public String getDisplayName() {
            return displayName(modId);
        }

        @Override
        public String getDescription() {
            return description(modId);
        }

        @Override
        public net.neoforged.neoforgespi.language.IModFileInfo getOwningFile() {
            return new net.neoforged.fml.loading.moddiscovery.ModFileInfo(modId);
        }

        @Override
        public List<? extends ModVersion> getDependencies() {
            List<ModVersion> out = new ArrayList<>();

            for (ModDependency dependency : dependencies(modId)) {
                out.add(new NeoModVersion(dependency));
            }

            return out;
        }

        @Override
        public Optional<String> getLogoFile() {
            return Optional.empty();
        }

        @Override
        public boolean getLogoBlur() {
            return true;
        }

        @Override
        public Optional<String> getUpdateURL() {
            return Optional.empty();
        }

        @Override
        public Map<String, Object> getModProperties() {
            return Map.of();
        }

        @Override
        public String toString() {
            return "Mod Info: " + modId + " " + version(modId);
        }
    }

    private record NeoModVersion(ModDependency dependency)
            implements net.neoforged.neoforgespi.language.IModInfo.ModVersion {
        @Override
        public String getModId() {
            return dependency.modId();
        }

        @Override
        public VersionRange getVersionRange() {
            return VersionRange.createFromVersionSpec(dependency.versions().raw());
        }

        @Override
        public boolean isMandatory() {
            return dependency.kind() == ModDependency.Kind.REQUIRED;
        }

        @Override
        public net.neoforged.neoforgespi.language.IModInfo.Ordering getOrdering() {
            return switch (dependency.ordering()) {
                case BEFORE -> net.neoforged.neoforgespi.language.IModInfo.Ordering.BEFORE;
                case AFTER -> net.neoforged.neoforgespi.language.IModInfo.Ordering.AFTER;
                case NONE -> net.neoforged.neoforgespi.language.IModInfo.Ordering.NONE;
            };
        }

        @Override
        public net.neoforged.neoforgespi.language.IModInfo.DependencySide getSide() {
            return net.neoforged.neoforgespi.language.IModInfo.DependencySide.BOTH;
        }
    }

    // ------------------------------------------------------------------- Forge

    private static final class ForgeInfo implements net.minecraftforge.forgespi.language.IModInfo {
        private final String modId;

        ForgeInfo(String modId) {
            this.modId = modId;
        }

        @Override
        public String getModId() {
            return modId;
        }

        @Override
        public String getNamespace() {
            return modId;
        }

        @Override
        public ArtifactVersion getVersion() {
            return new DefaultArtifactVersion(version(modId));
        }

        @Override
        public String getDisplayName() {
            return displayName(modId);
        }

        @Override
        public String getDescription() {
            return description(modId);
        }

        @Override
        public net.minecraftforge.forgespi.language.IModFileInfo getOwningFile() {
            return new net.minecraftforge.fml.loading.moddiscovery.ModFileInfo(modId);
        }

        @Override
        public List<? extends ModVersion> getDependencies() {
            List<ModVersion> out = new ArrayList<>();

            for (ModDependency dependency : dependencies(modId)) {
                out.add(new ForgeModVersion(dependency));
            }

            return out;
        }

        @Override
        public Optional<String> getLogoFile() {
            return Optional.empty();
        }

        @Override
        public boolean getLogoBlur() {
            return true;
        }

        @Override
        public Optional<String> getUpdateURL() {
            return Optional.empty();
        }

        @Override
        public Map<String, Object> getModProperties() {
            return Map.of();
        }

        @Override
        public String toString() {
            return "Mod Info: " + modId + " " + version(modId);
        }
    }

    private record ForgeModVersion(ModDependency dependency)
            implements net.minecraftforge.forgespi.language.IModInfo.ModVersion {
        @Override
        public String getModId() {
            return dependency.modId();
        }

        @Override
        public VersionRange getVersionRange() {
            return VersionRange.createFromVersionSpec(dependency.versions().raw());
        }

        @Override
        public boolean isMandatory() {
            return dependency.kind() == ModDependency.Kind.REQUIRED;
        }

        @Override
        public net.minecraftforge.forgespi.language.IModInfo.Ordering getOrdering() {
            return switch (dependency.ordering()) {
                case BEFORE -> net.minecraftforge.forgespi.language.IModInfo.Ordering.BEFORE;
                case AFTER -> net.minecraftforge.forgespi.language.IModInfo.Ordering.AFTER;
                case NONE -> net.minecraftforge.forgespi.language.IModInfo.Ordering.NONE;
            };
        }

        @Override
        public net.minecraftforge.forgespi.language.IModInfo.DependencySide getSide() {
            return net.minecraftforge.forgespi.language.IModInfo.DependencySide.BOTH;
        }
    }

    // Shared by both spellings of ModFileInfo, which is why they live here rather
    // than being duplicated a third and fourth time.

    public static String modLoaderOf(String modId) {
        return loaderOf(modId);
    }

    public static String licenseOf(String modId) {
        return license(modId);
    }

    public static String fileNameOf(String modId) {
        return fileName(modId);
    }

    public static String versionOf(String modId) {
        return version(modId);
    }

    /** Whether Octo has this mod at all, which is the question these lookups exist to answer. */
    public static boolean isPresent(String modId) {
        return OctoRuntime.isRunning() && OctoRuntime.get().isModLoaded(modId);
    }

    /** Every mod Octo loaded, in load order. */
    public static List<String> loadedIds() {
        if (!OctoRuntime.isRunning()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        OctoRuntime.get().loadedMods().forEach(loaded -> out.add(loaded.id()));
        return out;
    }
}
