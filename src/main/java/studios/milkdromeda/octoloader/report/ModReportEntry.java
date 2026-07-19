package studios.milkdromeda.octoloader.report;

import studios.milkdromeda.octoloader.detect.DetectedMod;

/**
 * One row of the compatibility report.
 *
 * @param mod    detection result for the jar
 * @param status final verdict
 * @param reason human-readable explanation (why it loaded, or why it could not)
 */
public record ModReportEntry(DetectedMod mod, CompatStatus status, String reason) {
}
