package studios.milkdromeda.octoloader.report;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects per-mod verdicts and renders them as a log table and a markdown
 * report file in the game directory.
 */
public final class CompatReport {
    private final List<ModReportEntry> entries = Collections.synchronizedList(new ArrayList<>());

    public void add(ModReportEntry entry) {
        entries.add(entry);
    }

    public List<ModReportEntry> entries() {
        return List.copyOf(entries);
    }

    public boolean hasProblems() {
        return entries.stream().anyMatch(e ->
                e.status() != CompatStatus.NATIVE && e.status() != CompatStatus.TRANSLATED);
    }

    public void logTable(Logger logger) {
        if (entries.isEmpty()) {
            logger.info("Octo Loader: no mod jars found to inspect");
            return;
        }
        String[] headers = {"File", "Ecosystem", "Mod ID", "Version", "Target MC", "Status", "Notes"};
        List<String[]> rows = new ArrayList<>();
        for (ModReportEntry e : entries) {
            rows.add(new String[]{
                    e.mod().path().getFileName().toString(),
                    e.mod().ecosystem().displayName(),
                    orDash(e.mod().modId()),
                    orDash(e.mod().version()),
                    orDash(e.mod().targetMinecraft()),
                    e.status().label(),
                    orDash(e.reason())
            });
        }
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) widths[i] = headers[i].length();
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) widths[i] = Math.max(widths[i], row[i].length());
        }
        logger.info("Octo Loader compatibility report ({} jar(s)):", entries.size());
        logger.info(formatRow(headers, widths));
        logger.info(separator(widths));
        for (String[] row : rows) logger.info(formatRow(row, widths));
    }

    public void writeMarkdown(Path gameDir) {
        Path file = gameDir.resolve("octo-report.md");
        StringBuilder sb = new StringBuilder();
        sb.append("# Octo Loader compatibility report\n\n");
        sb.append("Generated: ").append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\n\n");
        sb.append("| File | Ecosystem | Mod ID | Version | Target MC | Status | Notes |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (ModReportEntry e : entries) {
            sb.append("| ").append(e.mod().path().getFileName())
                    .append(" | ").append(e.mod().ecosystem().displayName())
                    .append(" | ").append(orDash(e.mod().modId()))
                    .append(" | ").append(orDash(e.mod().version()))
                    .append(" | ").append(orDash(e.mod().targetMinecraft()))
                    .append(" | ").append(e.status().label())
                    .append(" | ").append(orDash(e.reason()))
                    .append(" |\n");
        }
        try {
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            // The report file is best-effort; the log table already has everything.
        }
    }

    private static String formatRow(String[] cells, int[] widths) {
        StringBuilder sb = new StringBuilder("| ");
        for (int i = 0; i < cells.length; i++) {
            sb.append(cells[i]);
            sb.append(" ".repeat(widths[i] - cells[i].length()));
            sb.append(" | ");
        }
        return sb.toString().stripTrailing();
    }

    private static String separator(int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int width : widths) {
            sb.append("-".repeat(width + 2)).append("|");
        }
        return sb.toString();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s.replace("|", "\\|");
    }
}
