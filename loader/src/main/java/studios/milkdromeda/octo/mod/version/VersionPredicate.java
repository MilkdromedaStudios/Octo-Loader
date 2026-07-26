package studios.milkdromeda.octo.mod.version;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A version constraint, in whichever of the two dialects the mod's author used.
 *
 * <ul>
 *   <li>Fabric/Quilt style: {@code *}, {@code 1.20.1}, {@code >=1.20 <1.21},
 *       {@code ~1.20.1}, {@code ^2.0.0}, {@code 1.20.x}, alternatives with {@code ||}.</li>
 *   <li>Forge/NeoForge (Maven) style: {@code [1.20.1,1.21)}, {@code [1.19,)},
 *       {@code (,1.16.5]}, {@code [1.12.2]}.</li>
 * </ul>
 *
 * A bare Maven version such as {@code 1.20.1} is a <em>soft</em> requirement in
 * Maven's model and Forge inherits that, so Octo treats it as satisfied by
 * anything — matching what the original loader does rather than being stricter.
 */
public final class VersionPredicate implements Predicate<Version> {
    public static final VersionPredicate ANY = new VersionPredicate("*", List.of(List.of()));

    private final String raw;
    /** Outer list is OR, inner list is AND. An empty AND clause matches everything. */
    private final List<List<Clause>> alternatives;

    private VersionPredicate(String raw, List<List<Clause>> alternatives) {
        this.raw = raw;
        this.alternatives = alternatives;
    }

    public static VersionPredicate parse(String raw) {
        if (raw == null) {
            return ANY;
        }

        String text = raw.trim();

        if (text.isEmpty() || text.equals("*") || text.equalsIgnoreCase("any")) {
            return ANY;
        }

        List<List<Clause>> alternatives = new ArrayList<>();

        for (String alternative : splitAlternatives(text)) {
            List<Clause> clauses = parseAlternative(alternative.trim());

            if (clauses == null) {
                // Unparseable: better to admit everything than to lock a user out
                // of a mod because of a typo in a metadata file we did not write.
                return new VersionPredicate(text, List.of(List.of()));
            }

            alternatives.add(clauses);
        }

        return new VersionPredicate(text, alternatives);
    }

    /**
     * Parses in Maven's dialect, which Forge and NeoForge use for
     * {@code versionRange}.
     *
     * <p>The one difference that matters: a bare version is a <em>soft</em>
     * requirement in Maven — "this is the version I was built against", not "this
     * is the version I demand" — and Forge inherits that. The same string in a
     * Fabric {@code depends} block means exactly that version, so the dialect has
     * to come from the caller rather than be guessed from the text.
     */
    public static VersionPredicate parseRange(String raw) {
        if (raw == null) {
            return ANY;
        }

        String text = raw.trim();

        if (text.isEmpty() || text.equals("*")) {
            return ANY;
        }

        if (text.startsWith("[") || text.startsWith("(")) {
            return parse(text);
        }

        for (String operator : new String[] { ">", "<", "=", "~", "^" }) {
            if (text.contains(operator)) {
                return parse(text);
            }
        }

        return new VersionPredicate(text, List.of(List.of()));
    }

    private static List<String> splitAlternatives(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '[' || c == '(') {
                depth++;
            } else if (c == ']' || c == ')') {
                depth--;
            } else if (depth == 0 && c == '|' && i + 1 < text.length() && text.charAt(i + 1) == '|') {
                out.add(text.substring(start, i));
                start = i + 2;
                i++;
            }
        }

        out.add(text.substring(start));
        return out;
    }

    private static List<Clause> parseAlternative(String text) {
        if (text.isEmpty()) {
            return null;
        }

        return text.startsWith("[") || text.startsWith("(") ? parseMaven(text) : parseFabric(text);
    }

    // ---------------------------------------------------------------- Maven

    private static List<Clause> parseMaven(String text) {
        List<Clause> clauses = new ArrayList<>();

        for (String range : splitMavenRanges(text)) {
            List<Clause> parsed = parseMavenRange(range.trim());

            if (parsed == null) {
                return null;
            }

            clauses.addAll(parsed);
        }

        return clauses;
    }

    private static List<String> splitMavenRanges(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '[' || c == '(') {
                depth++;
            } else if (c == ']' || c == ')') {
                depth--;

                if (depth == 0 && i + 1 < text.length()) {
                    out.add(text.substring(start, i + 1));
                    // Skip the separating comma.
                    start = text.indexOf(',', i) < 0 ? i + 1 : text.indexOf(',', i) + 1;
                    i = start - 1;
                }
            }
        }

        if (start < text.length()) {
            out.add(text.substring(start));
        }

        return out;
    }

    private static List<Clause> parseMavenRange(String range) {
        if (range.length() < 3) {
            return null;
        }

        char open = range.charAt(0);
        char close = range.charAt(range.length() - 1);

        if ((open != '[' && open != '(') || (close != ']' && close != ')')) {
            return null;
        }

        String body = range.substring(1, range.length() - 1);
        int comma = body.indexOf(',');

        if (comma < 0) {
            // [1.12.2] — a pinned version.
            Version pinned = Version.parse(body.trim());
            return List.of(new Clause(Op.GE, pinned), new Clause(Op.LE, pinned));
        }

        String lower = body.substring(0, comma).trim();
        String upper = body.substring(comma + 1).trim();
        List<Clause> clauses = new ArrayList<>(2);

        if (!lower.isEmpty()) {
            clauses.add(new Clause(open == '[' ? Op.GE : Op.GT, Version.parse(lower)));
        }

        if (!upper.isEmpty()) {
            clauses.add(new Clause(close == ']' ? Op.LE : Op.LT, Version.parse(upper)));
        }

        return clauses;
    }

    // --------------------------------------------------------------- Fabric

    private static List<Clause> parseFabric(String text) {
        List<Clause> clauses = new ArrayList<>();

        for (String term : text.split("[,\\s]+")) {
            if (term.isEmpty()) {
                continue;
            }

            Clause clause = parseFabricTerm(term);

            if (clause == null) {
                return null;
            }

            clauses.add(clause);
        }

        return clauses.isEmpty() ? null : clauses;
    }

    private static Clause parseFabricTerm(String term) {
        if (term.equals("*")) {
            return new Clause(Op.ANY, Version.zero());
        }

        if (term.startsWith(">=")) {
            return new Clause(Op.GE, Version.parse(term.substring(2)));
        }

        if (term.startsWith("<=")) {
            return new Clause(Op.LE, Version.parse(term.substring(2)));
        }

        if (term.startsWith(">")) {
            return new Clause(Op.GT, Version.parse(term.substring(1)));
        }

        if (term.startsWith("<")) {
            return new Clause(Op.LT, Version.parse(term.substring(1)));
        }

        if (term.startsWith("=")) {
            return new Clause(Op.EQ, Version.parse(term.substring(1)));
        }

        if (term.startsWith("~")) {
            // ~1.2.3 → >=1.2.3 and same major.minor
            return new Clause(Op.TILDE, Version.parse(term.substring(1)));
        }

        if (term.startsWith("^")) {
            // ^1.2.3 → >=1.2.3 and same major
            return new Clause(Op.CARET, Version.parse(term.substring(1)));
        }

        int wildcard = term.indexOf(".x");

        if (wildcard > 0 || term.endsWith(".*")) {
            String prefix = term.substring(0, wildcard > 0 ? wildcard : term.length() - 2);
            return new Clause(Op.PREFIX, Version.parse(prefix));
        }

        return new Clause(Op.EQ, Version.parse(term));
    }

    // -------------------------------------------------------------- testing

    @Override
    public boolean test(Version version) {
        for (List<Clause> alternative : alternatives) {
            boolean matched = true;

            for (Clause clause : alternative) {
                if (!clause.test(version)) {
                    matched = false;
                    break;
                }
            }

            if (matched) {
                return true;
            }
        }

        return false;
    }

    public boolean test(String version) {
        return test(Version.parse(version));
    }

    public boolean isAny() {
        for (List<Clause> alternative : alternatives) {
            if (alternative.isEmpty()) {
                return true;
            }

            if (alternative.size() == 1 && alternative.get(0).op == Op.ANY) {
                return true;
            }
        }

        return false;
    }

    /**
     * The lowest version this predicate could accept, or {@link Version#zero()} when unbounded.
     * Used to guess which Minecraft era a mod was written for when it says nothing else.
     */
    public Version lowerBound() {
        Version best = null;

        for (List<Clause> alternative : alternatives) {
            for (Clause clause : alternative) {
                if (clause.op == Op.GE || clause.op == Op.GT || clause.op == Op.EQ
                        || clause.op == Op.TILDE || clause.op == Op.CARET || clause.op == Op.PREFIX) {
                    if (best == null || clause.bound.compareTo(best) < 0) {
                        best = clause.bound;
                    }
                }
            }
        }

        return best == null ? Version.zero() : best;
    }

    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }

    private enum Op { ANY, EQ, GE, GT, LE, LT, TILDE, CARET, PREFIX }

    private record Clause(Op op, Version bound) {
        boolean test(Version version) {
            return switch (op) {
                case ANY -> true;
                case EQ -> version.compareTo(bound) == 0;
                case GE -> version.compareTo(bound) >= 0;
                case GT -> version.compareTo(bound) > 0;
                case LE -> version.compareTo(bound) <= 0;
                case LT -> version.compareTo(bound) < 0;
                case TILDE -> version.compareTo(bound) >= 0 && version.sharesPrefix(bound, 2);
                case CARET -> version.compareTo(bound) >= 0 && version.sharesPrefix(bound, 1);
                case PREFIX -> version.sharesPrefix(bound, bound.size());
            };
        }
    }
}
