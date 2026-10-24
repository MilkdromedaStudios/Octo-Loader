package studios.milkdromeda.octo.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;

/** A mod that passed resolution and is being loaded. */
public final class LoadedMod {
    private final ModCandidate candidate;
    private final List<Object> instances = new ArrayList<>();
    private final List<String> appliedFixes = new ArrayList<>();
    private Path effectivePath;
    private List<Path> extraRoots = List.of();
    private boolean fromAnotherVersion;
    private Throwable failure;

    public LoadedMod(ModCandidate candidate) {
        this.candidate = candidate;
        this.effectivePath = candidate.path();
    }

    public ModCandidate candidate() {
        return candidate;
    }

    public ModMetadata metadata() {
        return candidate.metadata();
    }

    public String id() {
        return candidate.id();
    }

    public ModFormat format() {
        return candidate.format();
    }

    public Era era() {
        return candidate.era();
    }

    /** The jar actually on the class path — a translated copy, when one was made. */
    public Path effectivePath() {
        return effectivePath;
    }

    public void effectivePath(Path value) {
        this.effectivePath = value;
    }

    /**
     * Every jar this mod's files are in, the effective one first.
     *
     * <p>One for an ordinary mod. The game itself can be several: a client jar
     * and the jar of anything Mojang ships alongside it.
     */
    public List<Path> rootJars() {
        if (extraRoots.isEmpty()) {
            return List.of(effectivePath);
        }

        List<Path> out = new ArrayList<>(1 + extraRoots.size());
        out.add(effectivePath);
        out.addAll(extraRoots);
        return out;
    }

    public void extraRoots(List<Path> value) {
        this.extraRoots = List.copyOf(value);
    }

    /**
     * Whether this mod was built for a Minecraft other than the one running and
     * is being carried across: rebuilt into today's names, with whatever the
     * game no longer has stood in for or bypassed.
     */
    public boolean isFromAnotherVersion() {
        return fromAnotherVersion;
    }

    public void fromAnotherVersion(boolean value) {
        this.fromAnotherVersion = value;
    }

    /** One of the mods the game, the JVM and the loader present themselves as. */
    public boolean isBuiltin() {
        return format() == ModFormat.BUILTIN;
    }

    /** Entrypoint objects the bridges created, in construction order. */
    public List<Object> instances() {
        return instances;
    }

    /** What Octo had to change to make this mod run here. */
    public List<String> appliedFixes() {
        return appliedFixes;
    }

    public void recordFix(String description) {
        appliedFixes.add(description);
    }

    /** Non-null when the mod failed during loading and was skipped. */
    public Throwable failure() {
        return failure;
    }

    public void fail(Throwable error) {
        this.failure = error;
    }

    public boolean isFailed() {
        return failure != null;
    }

    @Override
    public String toString() {
        return id() + " " + metadata().version() + " [" + format().displayName() + "/" + era() + "]";
    }
}
