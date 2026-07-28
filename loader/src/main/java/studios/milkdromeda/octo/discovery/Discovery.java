package studios.milkdromeda.octo.discovery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import studios.milkdromeda.octo.mod.ModCandidate;

/**
 * Everything a pass over the mods folders found, including what it refused.
 *
 * <p>Discovery used to hand back a list of candidates and drop the rest on the
 * floor at debug level. That is precisely the shape of the complaint this loader
 * kept producing: a folder with forty jars in it, a log line saying zero mods
 * were found, and nothing anywhere naming a single file or a single reason. Each
 * file that was looked at now leaves a verdict behind, so the question "why is
 * this jar not loading" has an answer written down at the moment it was decided.
 */
public final class Discovery {
    /** What became of one file in the mods folder. */
    public enum Verdict {
        /** Metadata was read; the file produced at least one mod. */
        ACCEPTED,
        /** Readable, but nothing in it says it is a mod. Usually a library jar. */
        NOT_A_MOD,
        /**
         * Not a mod, and shipped inside one, so it is that mod's library and is
         * put on the class path rather than discarded.
         */
        LIBRARY,
        /** A metadata file is present and could not be parsed. Always an error. */
        BROKEN_METADATA,
        /** The archive itself could not be opened. Truncated download, or not a zip. */
        UNREADABLE
    }

    /**
     * @param file    the archive or exploded directory that was inspected
     * @param nested  {@code true} when this came out of another mod's jar-in-jar
     * @param detail  the reason, for anything other than {@link Verdict#ACCEPTED}
     * @param modIds  what it produced, for {@link Verdict#ACCEPTED}
     */
    public record Inspection(Path file, Verdict verdict, boolean nested, String detail, List<String> modIds) {
        public Inspection {
            modIds = List.copyOf(modIds);
        }

        @Override
        public String toString() {
            // Deliberately ASCII: this ends up on a Windows console, where a
            // dash that renders as a replacement character makes a diagnostic
            // look like a second thing that went wrong.
            return file.getFileName() + ": " + switch (verdict) {
                case ACCEPTED -> "loaded " + String.join(", ", modIds);
                case LIBRARY -> "library for " + detail;
                case NOT_A_MOD -> "not a mod (" + detail + ")";
                case BROKEN_METADATA -> "broken metadata - " + detail;
                case UNREADABLE -> "could not be read - " + detail;
            };
        }
    }

    private final List<Path> directories;
    private final List<ModCandidate> candidates = new ArrayList<>();
    private final List<Inspection> inspections = new ArrayList<>();
    private final List<Path> libraries = new ArrayList<>();

    Discovery(List<Path> directories) {
        this.directories = List.copyOf(directories);
    }

    /** The folders that were scanned, whether or not they existed. */
    public List<Path> directories() {
        return directories;
    }

    public List<ModCandidate> candidates() {
        return List.copyOf(candidates);
    }

    public List<Inspection> inspections() {
        return List.copyOf(inspections);
    }

    /** Files that were looked at and produced no mod, for any reason. */
    public List<Inspection> rejections() {
        return inspections.stream().filter(inspection -> inspection.verdict() != Verdict.ACCEPTED).toList();
    }

    /**
     * The rejections that are a defect rather than a decision.
     *
     * <p>A library jar in the mods folder is normal and is nobody's problem. A
     * jar carrying a {@code fabric.mod.json} that will not parse is a mod the
     * player installed and expects to be running, so it is not allowed to
     * disappear quietly.
     */
    public List<Inspection> failures() {
        return inspections.stream()
                .filter(inspection -> inspection.verdict() == Verdict.BROKEN_METADATA
                        || inspection.verdict() == Verdict.UNREADABLE)
                .toList();
    }

    /** How many files were inspected at the top level, ignoring nested jars. */
    public int filesInspected() {
        return (int) inspections.stream().filter(inspection -> !inspection.nested()).count();
    }

    /**
     * The jars a mod shipped inside itself that are not mods.
     *
     * <p>Jar-in-jar exists for exactly this: a mod bundles the libraries it needs
     * so the player does not have to install them. Discarding one because it has
     * no mod metadata is why Create, which ships Registrate that way, failed at
     * {@code NoClassDefFoundError: com/tterrag/registrate/AbstractRegistrate}
     * after loading perfectly.
     */
    public List<Path> libraries() {
        return List.copyOf(libraries);
    }

    void accept(Path file, boolean nested, List<String> modIds) {
        inspections.add(new Inspection(file, Verdict.ACCEPTED, nested, "", modIds));
    }

    void library(Path file, String owner) {
        libraries.add(file);
        inspections.add(new Inspection(file, Verdict.LIBRARY, true, owner, List.of()));
    }

    void reject(Path file, Verdict verdict, boolean nested, String detail) {
        // One inspection, one line. Gson in particular appends a documentation
        // URL on its own line, which turns a tidy report into a ragged one.
        inspections.add(new Inspection(file, verdict, nested,
                detail.replaceAll("\\s*\\R\\s*", " ").trim(), List.of()));
    }

    void add(List<ModCandidate> found) {
        candidates.addAll(found);
    }

    /** A block of lines fit to print in a log or a crash report. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("scanned " + directories);

        if (inspections.isEmpty()) {
            lines.add("  (no .jar, .zip or .litemod files were present)");
            return lines;
        }

        for (Inspection inspection : inspections) {
            lines.add("  " + inspection);
        }

        return lines;
    }
}
