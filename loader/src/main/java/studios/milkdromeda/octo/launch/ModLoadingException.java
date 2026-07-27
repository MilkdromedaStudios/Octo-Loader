package studios.milkdromeda.octo.launch;

import java.util.ArrayList;
import java.util.List;

/**
 * Thrown when mods that should have loaded did not.
 *
 * <p>The message is deliberately several lines long. This exception's whole
 * reason to exist is that it is the last thing the player sees before the
 * process ends, so it carries the evidence with it — which folders were
 * scanned, which files were rejected and why — rather than pointing at a log
 * the crash report will not contain.
 */
public final class ModLoadingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient List<String> detail;

    public ModLoadingException(String summary, List<String> detail) {
        this(summary, detail, null);
    }

    public ModLoadingException(String summary, List<String> detail, Throwable cause) {
        super(render(summary, detail), cause);
        this.detail = List.copyOf(detail);
    }

    /** The evidence lines, without the summary. */
    public List<String> detail() {
        return detail;
    }

    private static String render(String summary, List<String> detail) {
        List<String> lines = new ArrayList<>();
        lines.add(summary);
        lines.addAll(detail);
        lines.add("");
        lines.add("Octo refuses to start Minecraft without the mods you installed.");
        lines.add("  -Docto.lenient=true   start anyway, with whatever could be loaded");
        lines.add("  -Docto.safeMode=true  start with no mods at all");
        lines.add("  -Docto.debug=true     log every decision the loader makes");
        return String.join(System.lineSeparator(), lines);
    }
}
