package studios.milkdromeda.octoloader.knowledge;

import java.util.List;

/**
 * One documented era of a loader ecosystem: which Minecraft versions it spans,
 * what mappings its mods run on, and how far Octo Loader can take it.
 *
 * @param name            human-readable era name
 * @param mcPatterns      glob-like patterns matched against the mod's target MC
 *                        version tokens (e.g. {@code "26.*"}, {@code "1.*"}, {@code "*"})
 * @param runtimeMappings mapping namespace mods of this era are compiled against
 * @param support         {@code "translate"} or {@code "detect-only"}
 * @param summary         explanation used in compatibility reports
 */
public record Era(
        String name,
        List<String> mcPatterns,
        String runtimeMappings,
        String support,
        String summary
) {
    public boolean translatable() {
        return "translate".equals(support);
    }

    public boolean matches(List<String> versionTokens) {
        for (String pattern : mcPatterns) {
            if (pattern.equals("*")) return true;
            for (String token : versionTokens) {
                if (McVersions.globMatches(pattern, token)) return true;
            }
        }
        return false;
    }
}
