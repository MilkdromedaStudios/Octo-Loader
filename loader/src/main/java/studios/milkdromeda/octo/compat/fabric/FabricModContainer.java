package studios.milkdromeda.octo.compat.fabric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/** Presents an Octo-loaded mod — of any format — as a Fabric {@code ModContainer}. */
public final class FabricModContainer implements ModContainer {
    private final LoadedMod mod;
    private final ModMetadata metadata;

    private FabricModContainer(LoadedMod mod) {
        this.mod = mod;
        this.metadata = new FabricModMetadata(mod.metadata());
    }

    public static ModContainer of(LoadedMod mod) {
        return new FabricModContainer(mod);
    }

    @Override
    public ModMetadata getMetadata() {
        return metadata;
    }

    @Override
    public List<Path> getRootPaths() {
        return List.of(mod.effectivePath());
    }

    @Override
    public ModOrigin getOrigin() {
        return new ModOrigin() {
            @Override
            public Kind getKind() {
                return mod.candidate().isNested() ? Kind.NESTED : Kind.PATH;
            }

            @Override
            public List<Path> getPaths() {
                if (getKind() != Kind.PATH) {
                    throw new UnsupportedOperationException("not a path origin");
                }

                return List.of(mod.effectivePath());
            }

            @Override
            public String getParentModId() {
                if (getKind() != Kind.NESTED) {
                    throw new UnsupportedOperationException("not a nested origin");
                }

                return mod.candidate().parent().id();
            }

            @Override
            public String getParentSubLocation() {
                if (getKind() != Kind.NESTED) {
                    throw new UnsupportedOperationException("not a nested origin");
                }

                return mod.candidate().path().getFileName().toString();
            }
        };
    }

    @Override
    public Optional<ModContainer> getContainingMod() {
        if (!mod.candidate().isNested()) {
            return Optional.empty();
        }

        return OctoRuntime.get().mod(mod.candidate().parent().id()).map(FabricModContainer::of);
    }

    @Override
    public Collection<ModContainer> getContainedMods() {
        List<ModContainer> out = new ArrayList<>();

        for (LoadedMod candidate : OctoRuntime.get().loadedMods()) {
            if (candidate.candidate().isNested() && candidate.candidate().parent().id().equals(mod.id())) {
                out.add(of(candidate));
            }
        }

        return out;
    }

    @Override
    public Optional<Path> findPath(String file) {
        // Mod resources live inside the jar; Octo mounts each mod's jar as a
        // file system on demand rather than keeping every mod's zip open.
        Path root = mod.effectivePath();

        if (Files.isDirectory(root)) {
            Path candidate = root.resolve(file);
            return Files.exists(candidate) ? Optional.of(candidate) : Optional.empty();
        }

        try {
            java.nio.file.FileSystem fileSystem = JarFileSystems.get(root);
            Path candidate = fileSystem.getPath(file);
            return Files.exists(candidate) ? Optional.of(candidate) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    @Deprecated
    public Path getPath(String file) {
        return findPath(file).orElseGet(() -> mod.effectivePath().resolve(file));
    }

    @Override
    @Deprecated
    public Path getRootPath() {
        return getRootPaths().get(0);
    }
}
