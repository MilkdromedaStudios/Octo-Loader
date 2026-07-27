package studios.milkdromeda.octo.report;

import java.util.ArrayList;
import java.util.List;

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

    private final List<Problem> problems = new ArrayList<>();

    public synchronized void add(Severity severity, String source, String summary, String detail) {
        problems.add(new Problem(severity, source, summary, detail == null ? "" : detail));
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
        return problems.isEmpty();
    }

    public synchronized boolean hasErrors() {
        return problems.stream().anyMatch(problem -> problem.severity() == Severity.ERROR);
    }

    /** One line, for the title of whatever is showing this. */
    public synchronized String headline() {
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
