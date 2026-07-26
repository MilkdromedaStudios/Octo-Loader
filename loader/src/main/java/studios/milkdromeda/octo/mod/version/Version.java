package studios.milkdromeda.octo.mod.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A deliberately forgiving version.
 *
 * <p>Octo has to compare versions written by fifteen years of modders who never
 * agreed on a scheme: {@code 1.0.0}, {@code b1.7.3}, {@code 1.7.10-10.13.4.1614-1.7.10},
 * {@code 4.2.1+1.20.1}, {@code 2.0.0-beta.3}. Rather than reject anything, the
 * parser splits into numeric and alphabetic runs and compares component-wise,
 * with the usual rule that a pre-release suffix sorts before the release.
 */
public final class Version implements Comparable<Version> {
    private static final Version ZERO = new Version("0", List.of(new Component("0")), "");

    private final String raw;
    private final List<Component> components;
    private final String preRelease;

    private Version(String raw, List<Component> components, String preRelease) {
        this.raw = raw;
        this.components = components;
        this.preRelease = preRelease;
    }

    public static Version zero() {
        return ZERO;
    }

    public static Version parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ZERO;
        }

        String text = raw.trim();

        // Build metadata never participates in ordering.
        int plus = text.indexOf('+');
        String core = plus >= 0 ? text.substring(0, plus) : text;

        // A '-' introduces a pre-release only when what follows is not just more
        // version numbers; "1.7.10-10.13.4" is a compound version, "1.0-beta" is not.
        String preRelease = "";
        int dash = core.indexOf('-');

        if (dash >= 0) {
            String tail = core.substring(dash + 1);

            if (!tail.isEmpty() && !Character.isDigit(tail.charAt(0))) {
                preRelease = tail.toLowerCase(Locale.ROOT);
                core = core.substring(0, dash);
            } else {
                core = core.replace('-', '.');
            }
        }

        List<Component> components = split(core);

        if (components.isEmpty()) {
            components = List.of(new Component("0"));
        }

        return new Version(text, components, preRelease);
    }

    private static List<Component> split(String core) {
        List<Component> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        Boolean numeric = null;

        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);

            if (c == '.' || c == '_') {
                flush(out, buffer);
                numeric = null;
                continue;
            }

            boolean digit = Character.isDigit(c);

            if (numeric != null && digit != numeric) {
                flush(out, buffer);
            }

            numeric = digit;
            buffer.append(c);
        }

        flush(out, buffer);
        return out;
    }

    private static void flush(List<Component> out, StringBuilder buffer) {
        if (buffer.length() > 0) {
            out.add(new Component(buffer.toString()));
            buffer.setLength(0);
        }
    }

    public String raw() {
        return raw;
    }

    /** The n-th component as a number, or {@code 0} when absent or non-numeric. */
    public int part(int index) {
        if (index >= components.size()) {
            return 0;
        }

        Component component = components.get(index);
        return component.numeric ? (int) Math.min(Integer.MAX_VALUE, component.number) : 0;
    }

    public int size() {
        return components.size();
    }

    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    /** {@code true} when this version shares its first {@code depth} components with {@code other}. */
    public boolean sharesPrefix(Version other, int depth) {
        for (int i = 0; i < depth; i++) {
            if (compareComponent(i, other) != 0) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int compareTo(Version other) {
        int size = Math.max(components.size(), other.components.size());

        for (int i = 0; i < size; i++) {
            int result = compareComponent(i, other);

            if (result != 0) {
                return result;
            }
        }

        if (preRelease.isEmpty() != other.preRelease.isEmpty()) {
            // 1.0.0-beta < 1.0.0
            return preRelease.isEmpty() ? 1 : -1;
        }

        return preRelease.compareTo(other.preRelease);
    }

    private int compareComponent(int index, Version other) {
        Component mine = index < components.size() ? components.get(index) : Component.ABSENT;
        Component theirs = index < other.components.size() ? other.components.get(index) : Component.ABSENT;
        return mine.compareTo(theirs);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        // Consistent with equals: only the significant parts take part.
        int hash = 1;

        for (Component component : components) {
            if (component.numeric && component.number == 0) {
                continue;
            }

            hash = hash * 31 + (component.numeric ? Long.hashCode(component.number) : component.text.hashCode());
        }

        return hash * 31 + preRelease.hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }

    private static final class Component implements Comparable<Component> {
        static final Component ABSENT = new Component("0");

        final String text;
        final boolean numeric;
        final long number;

        Component(String text) {
            this.text = text;
            boolean digits = !text.isEmpty();

            for (int i = 0; digits && i < text.length(); i++) {
                digits = Character.isDigit(text.charAt(i));
            }

            this.numeric = digits;
            this.number = digits ? parseLong(text) : 0;
        }

        private static long parseLong(String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
        }

        @Override
        public int compareTo(Component other) {
            if (numeric && other.numeric) {
                return Long.compare(number, other.number);
            }

            if (numeric != other.numeric) {
                // A numeric component outranks a textual one: 1.2 > 1.rc
                return numeric ? 1 : -1;
            }

            return text.compareToIgnoreCase(other.text);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Component other && compareTo(other) == 0;
        }

        @Override
        public int hashCode() {
            return numeric ? Long.hashCode(number) : Objects.hash(text.toLowerCase(Locale.ROOT));
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
