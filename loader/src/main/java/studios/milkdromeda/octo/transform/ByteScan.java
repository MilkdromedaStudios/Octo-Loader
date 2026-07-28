package studios.milkdromeda.octo.transform;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Answers "could this class file possibly mention any of these names" without
 * parsing it.
 *
 * <p>Every class a class file refers to has its internal name in the constant
 * pool as UTF-8, so a substring search over the raw bytes is a sound over-
 * approximation: it can say yes when the match was a coincidence, and it can
 * never say no when the reference is really there. That matters because the
 * transformers using this are asked about every one of the tens of thousands of
 * classes a modded game loads, and almost none of them are interesting.
 *
 * <p>One pass, with a lookup table on the first byte so that the usual answer —
 * no — costs a comparison per byte and no allocation at all.
 */
public final class ByteScan {
    private final byte[][] needles;
    private final boolean[] starts = new boolean[256];

    public ByteScan(Collection<String> patterns) {
        this.needles = patterns.stream()
                .filter(pattern -> pattern != null && !pattern.isEmpty())
                .map(pattern -> pattern.getBytes(StandardCharsets.UTF_8))
                .toArray(byte[][]::new);

        for (byte[] needle : needles) {
            starts[needle[0] & 0xFF] = true;
        }
    }

    public boolean isEmpty() {
        return needles.length == 0;
    }

    /** @return {@code true} when any of the patterns appears in {@code haystack} */
    public boolean matches(byte[] haystack) {
        for (int i = 0; i < haystack.length; i++) {
            if (!starts[haystack[i] & 0xFF]) {
                continue;
            }

            for (byte[] needle : needles) {
                if (matchesAt(haystack, i, needle)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean matchesAt(byte[] haystack, int offset, byte[] needle) {
        if (offset + needle.length > haystack.length) {
            return false;
        }

        for (int i = 0; i < needle.length; i++) {
            if (haystack[offset + i] != needle[i]) {
                return false;
            }
        }

        return true;
    }
}
