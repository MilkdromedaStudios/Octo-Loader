package studios.milkdromeda.octoloader.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small helpers for reasoning about Minecraft version strings found in mod
 * metadata, which arrive in many shapes: {@code "26.2"}, {@code "~1.20.1"},
 * {@code "[1.21.1,1.22)"}, {@code ">=26.1 <26.3"}, {@code "1.12.2"}...
 */
public final class McVersions {
    private static final Pattern VERSION_TOKEN = Pattern.compile("\\d+(?:\\.\\d+)+(?:[.-]\\w+)*");

    private McVersions() {
    }

    /** Extracts every version-looking token from a free-form range string. */
    public static List<String> extractTokens(String range) {
        List<String> tokens = new ArrayList<>();
        if (range == null || range.isBlank()) return tokens;
        Matcher m = VERSION_TOKEN.matcher(range);
        while (m.find()) tokens.add(m.group());
        return tokens;
    }

    /** Minimal glob: {@code *} matches any suffix, everything else is literal. */
    public static boolean globMatches(String pattern, String value) {
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(value);
    }

    /**
     * Whether the mod's declared target could include the running Minecraft
     * version. Best-effort: an undeclared target yields {@code true} (we let
     * translators decide) and a declared one must share the running version's
     * major prefix or explicitly contain it.
     */
    public static boolean couldTarget(String declaredRange, String runningVersion) {
        List<String> tokens = extractTokens(declaredRange);
        if (tokens.isEmpty()) return true;
        String majorPrefix = runningVersion.split("\\.")[0] + ".";
        for (String token : tokens) {
            if (token.equals(runningVersion) || token.startsWith(majorPrefix)) return true;
        }
        return false;
    }
}
