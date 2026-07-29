package studios.milkdromeda.octo.report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything that went wrong during a launch, kept so it can be shown.
 *
 * <p>Octo has now been through both wrong answers to a mod that will not load.
 * It used to write one warning and start Minecraft anyway, which is invisible —
 * the player gets a game that looks fine and has nothing in it. Then it refused
 * to start at all, which is visible but useless: a folder of forty mods on a
 * Minecraft four versions newer than any of them will always have something to
 * complain about, and none of it is a reason to be unable to play.
 *
 * <p>Forge got this right years ago. Boot the game, then put the failures in
 * front of the player where they can read them. So nothing here throws;
 * everything is collected, written to a file, printed to the log, and handed to
 * whatever can display it.
 */
public final class LoadingReport {
    public enum Severity {
        /** A mod the player installed is not running. */
        ERROR,
        /** A mod is running, but not as its author built it. */
        WARNING
    }

    /**
     * @param source  the mod id or file this is about
     * @param summary one line, the thing that went wrong
     * @param detail  the evidence: a stack trace, a parser message, a list
     */
    public record Problem(Severity severity, String source, String summary, String detail) {
        @Override
        public String toString() {
            return severity + " " + source + ": " + summary;
        }
    }

    /**
     * The game itself stopping, which is a different thing from a mod failing.
     *
     * @param phase  what Minecraft was doing, in its own words — the crash
     *               report's {@code Description}, usually "Initializing game"
     * @param detail the exception and as much of the stack as is worth reading
     * @param file   Minecraft's own crash report, when it managed to write one
     */
    public record Crash(String phase, String detail, Path file) {
        public String title() {
            // Plain ASCII, because this line is the first one in the report file
            // and in the log, and a console on a legacy code page renders
            // anything else as a question mark.
            return phase == null || phase.isBlank() ? "Minecraft stopped" : "Minecraft stopped - " + phase;
        }
    }

    private final List<Problem> problems = new ArrayList<>();
    private Crash crash;

    public synchronized void add(Severity severity, String source, String summary, String detail) {
        problems.add(new Problem(severity, source, summary, detail == null ? "" : detail));
    }

    /**
     * Records that the game stopped, replacing any earlier crash.
     *
     * <p>Later is better here: the first throwable a dying game produces is often
     * a consequence of the one before it, and the crash report Minecraft writes
     * last is the one with the description in it.
     */
    public synchronized void crashed(String phase, String detail, Path file) {
        crash = new Crash(phase == null ? "" : phase.strip(), detail == null ? "" : detail, file);
    }

    public synchronized Optional<Crash> crash() {
        return Optional.ofNullable(crash);
    }

    public synchronized boolean hasCrash() {
        return crash != null;
    }

    public synchronized void error(String source, String summary, String detail) {
        add(Severity.ERROR, source, summary, detail);
    }

    public synchronized void warning(String source, String summary, String detail) {
        add(Severity.WARNING, source, summary, detail);
    }

    public synchronized List<Problem> problems() {
        return List.copyOf(problems);
    }

    public synchronized List<Problem> of(Severity severity) {
        return problems.stream().filter(problem -> problem.severity() == severity).toList();
    }

    public synchronized boolean isEmpty() {
        return problems.isEmpty() && crash == null;
    }

    public synchronized boolean hasErrors() {
        return crash != null || problems.stream().anyMatch(problem -> problem.severity() == Severity.ERROR);
    }

    /** One line, for the title of whatever is showing this. */
    public synchronized String headline() {
        if (crash != null) {
            return crash.title();
        }

        int errors = of(Severity.ERROR).size();
        int warnings = of(Severity.WARNING).size();

        if (errors == 0 && warnings == 0) {
            return "Every mod loaded";
        }

        if (errors == 0) {
            return warnings + (warnings == 1 ? " mod needed" : " mods needed") + " adjusting to run here";
        }

        String text = errors + (errors == 1 ? " mod is not running" : " mods are not running");
        return warnings == 0 ? text : text + ", and " + warnings + " needed adjusting";
    }

    /** The whole thing as text, for the log file and for the window. */
    public synchronized String render() {
        StringBuilder out = new StringBuilder();
        out.append(headline()).append(System.lineSeparator());

        if (crash != null) {
            out.append(System.lineSeparator()).append("What Minecraft was doing").append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("  ").append(crash.phase().isBlank() ? "(not stated)" : crash.phase())
                    .append(System.lineSeparator());

            if (crash.file() != null) {
                out.append("  Minecraft's own crash report: ").append(crash.file()).append(System.lineSeparator());
            }

            CrashReading reading = CrashReading.of(crash.detail());
            out.append(System.lineSeparator()).append("What that usually means").append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("  ").append(reading.meaning()).append(System.lineSeparator());

            for (String step : reading.steps()) {
                out.append("    - ").append(step).append(System.lineSeparator());
            }

            if (!crash.detail().isBlank()) {
                out.append(System.lineSeparator()).append("The failure itself").append(System.lineSeparator())
                        .append(System.lineSeparator());
                crash.detail().lines().forEach(line ->
                        out.append("      ").append(line).append(System.lineSeparator()));
            }
        }

        for (Severity severity : Severity.values()) {
            List<Problem> selected = of(severity);

            if (selected.isEmpty()) {
                continue;
            }

            out.append(System.lineSeparator())
                    .append(severity == Severity.ERROR ? "Not running" : "Loaded with changes")
                    .append(System.lineSeparator());

            for (Problem problem : selected) {
                out.append(System.lineSeparator())
                        .append("  ").append(problem.source()).append(" - ").append(problem.summary())
                        .append(System.lineSeparator());

                if (!problem.detail().isBlank()) {
                    problem.detail().lines().forEach(line ->
                            out.append("      ").append(line).append(System.lineSeparator()));
                }
            }
        }

        return out.toString();
    }
}
