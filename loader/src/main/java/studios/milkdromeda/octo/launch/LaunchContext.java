package studios.milkdromeda.octo.launch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import studios.milkdromeda.octo.mod.Side;

/** Everything the loader was told at startup. */
public final class LaunchContext {
    private final Path gameDir;
    private final List<Path> modDirs;
    private final Path configDir;
    private final List<Path> gameJars;
    private final Side side;
    private final String minecraftVersion;
    private final String mainClass;
    private final List<String> launchArguments;
    private final boolean development;
    private final CompatOptions compat;

    private LaunchContext(Builder builder) {
        this.gameDir = builder.gameDir;
        this.modDirs = List.copyOf(builder.modDirs);
        this.configDir = builder.configDir != null ? builder.configDir : builder.gameDir.resolve("config");
        this.gameJars = List.copyOf(builder.gameJars);
        this.side = builder.side;
        this.minecraftVersion = builder.minecraftVersion;
        this.mainClass = builder.mainClass;
        this.launchArguments = List.copyOf(builder.launchArguments);
        this.development = builder.development;
        this.compat = builder.compat;
    }

    public static Builder builder(Path gameDir) {
        return new Builder(gameDir);
    }

    public Path gameDir() {
        return gameDir;
    }

    public List<Path> modDirs() {
        return modDirs;
    }

    public Path configDir() {
        return configDir;
    }

    /** Where Octo keeps caches, unpacked nested jars and translated mods. */
    public Path octoDir() {
        return gameDir.resolve(".octo");
    }

    public List<Path> gameJars() {
        return gameJars;
    }

    public Side side() {
        return side;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public String mainClass() {
        return mainClass;
    }

    public List<String> launchArguments() {
        return launchArguments;
    }

    public boolean isDevelopment() {
        return development;
    }

    public CompatOptions compat() {
        return compat;
    }

    /** How hard Octo should try to make an incompatible mod work. */
    public static final class CompatOptions {
        private boolean translateOldMods = true;
        private boolean relaxVersionChecks = true;
        private boolean stubMissingApi = true;
        private boolean allowCrossEcosystem = true;
        private boolean strict = true;

        /** Remap and rewrite mods built for an older Minecraft. */
        public boolean translateOldMods() {
            return translateOldMods;
        }

        /** Treat an unsatisfiable Minecraft version bound as a warning, not a refusal. */
        public boolean relaxVersionChecks() {
            return relaxVersionChecks;
        }

        /** Synthesise classes and members a mod references that no longer exist. */
        public boolean stubMissingApi() {
            return stubMissingApi;
        }

        /** Let a Fabric mod see a Forge mod, and the reverse. */
        public boolean allowCrossEcosystem() {
            return allowCrossEcosystem;
        }

        /**
         * Abort the launch when a mod that should have loaded did not.
         *
         * <p>On by default, and deliberately so. The alternative — carrying on
         * into a game with no mods in it — is the single worst thing this loader
         * can do, because it looks exactly like a launch that worked. Every
         * report of "Octo isn't loading my mods" is a launch that had already
         * decided which file it could not read and wrote one line about it
         * before starting Minecraft anyway. A loader that stops and says what
         * went wrong is fixable; one that shrugs is not.
         *
         * <p>{@code -Docto.lenient=true} restores the old behaviour for anyone
         * who would rather play without a mod than not play.
         */
        public boolean strict() {
            return strict;
        }

        public CompatOptions translateOldMods(boolean value) {
            this.translateOldMods = value;
            return this;
        }

        public CompatOptions relaxVersionChecks(boolean value) {
            this.relaxVersionChecks = value;
            return this;
        }

        public CompatOptions stubMissingApi(boolean value) {
            this.stubMissingApi = value;
            return this;
        }

        public CompatOptions allowCrossEcosystem(boolean value) {
            this.allowCrossEcosystem = value;
            return this;
        }

        public CompatOptions strict(boolean value) {
            this.strict = value;
            return this;
        }
    }

    public static final class Builder {
        private final Path gameDir;
        private final List<Path> modDirs = new ArrayList<>();
        private final List<Path> gameJars = new ArrayList<>();
        private final List<String> launchArguments = new ArrayList<>();
        private Path configDir;
        private Side side = Side.CLIENT;
        private String minecraftVersion = "";
        private String mainClass;
        private boolean development;
        private CompatOptions compat = new CompatOptions();

        private Builder(Path gameDir) {
            this.gameDir = gameDir.toAbsolutePath().normalize();
        }

        public Builder modDir(Path value) {
            this.modDirs.add(value);
            return this;
        }

        public Builder configDir(Path value) {
            this.configDir = value;
            return this;
        }

        public Builder gameJar(Path value) {
            this.gameJars.add(value);
            return this;
        }

        public Builder side(Side value) {
            this.side = value;
            return this;
        }

        public Builder minecraftVersion(String value) {
            this.minecraftVersion = value == null ? "" : value;
            return this;
        }

        public Builder mainClass(String value) {
            this.mainClass = value;
            return this;
        }

        public Builder launchArguments(List<String> value) {
            this.launchArguments.clear();
            this.launchArguments.addAll(value);
            return this;
        }

        public Builder development(boolean value) {
            this.development = value;
            return this;
        }

        public Builder compat(CompatOptions value) {
            this.compat = value;
            return this;
        }

        public LaunchContext build() {
            if (modDirs.isEmpty()) {
                modDirs.add(gameDir.resolve("mods"));
            }

            return new LaunchContext(this);
        }
    }
}
