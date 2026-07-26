package studios.milkdromeda.octo.bridge.quilt;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.ModMetadata;

import studios.milkdromeda.octo.runtime.LoadedMod;

/** Presents an Octo-loaded mod through Quilt's container interface. */
public final class QuiltModContainer implements ModContainer {
    private final LoadedMod mod;

    private QuiltModContainer(LoadedMod mod) {
        this.mod = mod;
    }

    public static ModContainer of(LoadedMod mod) {
        return new QuiltModContainer(mod);
    }

    @Override
    public ModMetadata metadata() {
        return new Metadata(mod);
    }

    @Override
    public Path rootPath() {
        return mod.effectivePath();
    }

    private record Metadata(LoadedMod mod) implements ModMetadata {
        @Override
        public String id() {
            return mod.id();
        }

        @Override
        public String group() {
            return "octo";
        }

        @Override
        public String name() {
            return mod.metadata().name();
        }

        @Override
        public String description() {
            return mod.metadata().description();
        }

        @Override
        public String version() {
            return mod.metadata().version().raw();
        }

        @Override
        public Collection<String> licenses() {
            String license = mod.metadata().license();
            return license.isBlank() ? List.of() : List.of(license);
        }
    }
}
