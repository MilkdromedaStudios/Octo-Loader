package studios.milkdromeda.octoloader.translate;

import studios.milkdromeda.octoloader.detect.DetectedMod;
import studios.milkdromeda.octoloader.report.ModReportEntry;

/**
 * A pluggable ecosystem translator, discovered via {@link java.util.ServiceLoader}.
 * Implementations turn a foreign mod jar into a Fabric mod jar in the Octo
 * cache, or explain why they cannot.
 */
public interface Translator {
    /** Stable identifier used in logs and reports. */
    String id();

    /** Whether this translator wants to handle the given mod. */
    boolean supports(DetectedMod mod);

    /**
     * Translates the mod. Implementations must not throw for expected failure
     * modes — they return an entry with the appropriate status and reason.
     */
    ModReportEntry translate(DetectedMod mod, TranslationContext context);
}
