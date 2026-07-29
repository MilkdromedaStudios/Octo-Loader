package studios.milkdromeda.octo.mod;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import studios.milkdromeda.octo.compat.Era;

/** A mod found on disk, before resolution has decided whether it will be loaded. */
public final class ModCandidate {
    private final Path path;
    private final ModCandidate parent;
    private ModMetadata metadata;
    private Era era = Era.UNKNOWN;
    private BytecodeIndex bytecode = BytecodeIndex.EMPTY;
    private final List<String> notes = new ArrayList<>();

    public ModCandidate(Path path, ModMetadata metadata, ModCandidate parent) {
        this.path = Objects.requireNonNull(path, "path");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.parent = parent;
    }

    public Path path() {
        return path;
    }

    /** The jar this one was nested inside, or {@code null} when it sat in the mods folder. */
    public ModCandidate parent() {
        return parent;
    }

    public boolean isNested() {
        return parent != null;
    }

    public ModMetadata metadata() {
        return metadata;
    }

    public void metadata(ModMetadata replacement) {
        this.metadata = replacement;
    }

    public String id() {
        return metadata.id();
    }

    public ModFormat format() {
        return metadata.format();
    }

    /** The Minecraft generation this mod was compiled against, once detection has run. */
    public Era era() {
        return era;
    }

    public void era(Era value) {
        this.era = value;
    }

    /**
     * What this mod's own classes said about themselves when the jar was scanned.
     *
     * <p>Empty for a mod discovered without a bytecode pass, which in practice
     * means only the fixtures: discovery always scans.
     */
    public BytecodeIndex bytecode() {
        return bytecode;
    }

    public void bytecode(BytecodeIndex index) {
        this.bytecode = index == null ? BytecodeIndex.EMPTY : index;
    }

    /** Human-readable record of everything Octo had to do to make this mod loadable. */
    public List<String> notes() {
        return notes;
    }

    public void note(String note) {
        notes.add(note);
    }

    @Override
    public String toString() {
        return metadata + " @ " + path.getFileName();
    }
}
