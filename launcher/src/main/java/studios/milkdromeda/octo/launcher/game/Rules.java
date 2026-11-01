package studios.milkdromeda.octo.launcher.game;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The rules in a version's JSON that say which libraries and arguments apply
 * on which system: as the official launcher reads them, nothing allowed
 * unless a rule allows it, and the last rule that matches decides.
 */
public final class Rules {
    /** The system the game runs on, in the names the version files use. */
    public record Platform(String os, String arch, String osVersion) {
        public static Platform current() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String os = name.contains("win") ? "windows" : name.contains("mac") || name.contains("darwin") ? "osx"
                    : "linux";
            String version = System.getProperty("os.version", "");
            return new Platform(os, System.getProperty("os.arch", "").toLowerCase(Locale.ROOT),
                    os.equals("windows") ? windowsBuild(version) : version);
        }

        /**
         * Java says only "10.0" on Windows 10 and 11 alike; the version rules
         * need the build, which {@code ver} prints: "[Version 10.0.22631.4317]".
         */
        private static String windowsBuild(String fallback) {
            String known = windowsVersion;

            if (known != null) {
                return known;
            }

            known = fallback;

            try {
                Process process = new ProcessBuilder("cmd.exe", "/c", "ver").redirectErrorStream(true).start();

                if (process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    String out = new String(process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    java.util.regex.Matcher matcher = Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(out);

                    if (matcher.find()) {
                        known = matcher.group(1);
                    }
                } else {
                    process.destroy();
                }
            } catch (Exception e) {
                // Java's own, then.
            }

            windowsVersion = known;
            return known;
        }

        public boolean is64Bit() {
            return arch.contains("64") || arch.equals("aarch64");
        }

        public boolean isArm() {
            return arch.equals("aarch64") || arch.startsWith("arm");
        }

        /** The 32-bit x86 that the "x86" rules mean. */
        boolean isX86() {
            return arch.equals("x86") || arch.equals("i386") || arch.equals("i686");
        }

        public boolean isWindows() {
            return os.equals("windows");
        }
    }

    private static volatile String windowsVersion;

    private Rules() {
    }

    /** Whether something with these rules applies; no rules at all means it does. */
    public static boolean allows(JsonElement rules, Platform platform, Map<String, Boolean> features) {
        if (!(rules instanceof JsonArray list) || list.isEmpty()) {
            return true;
        }

        boolean allowed = false;

        for (JsonElement element : list) {
            if (!(element instanceof JsonObject rule)) {
                continue;
            }

            if (matches(rule, platform, features)) {
                allowed = !"disallow".equals(string(rule, "action"));
            }
        }

        return allowed;
    }

    private static boolean matches(JsonObject rule, Platform platform, Map<String, Boolean> features) {
        if (rule.get("os") instanceof JsonObject os) {
            String name = string(os, "name");

            if (name != null && !name.equals(platform.os())) {
                return false;
            }

            String arch = string(os, "arch");

            if (arch != null && !(arch.equals("x86") ? platform.isX86() : platform.arch().equals(arch))) {
                return false;
            }

            String version = string(os, "version");

            if (version != null) {
                try {
                    if (!Pattern.compile(version).matcher(platform.osVersion()).find()) {
                        return false;
                    }
                } catch (RuntimeException e) {
                    return false;
                }
            }

            // Newer files bound Windows versions by build: a range to sit in.
            if (os.get("versionRange") instanceof JsonObject range && !inRange(platform.osVersion(), range)) {
                return false;
            }
        }

        if (rule.get("features") instanceof JsonObject wanted) {
            for (Map.Entry<String, JsonElement> feature : wanted.entrySet()) {
                boolean value = feature.getValue().isJsonPrimitive() && feature.getValue().getAsBoolean();

                if (features.getOrDefault(feature.getKey(), false) != value) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean inRange(String version, JsonObject range) {
        String min = string(range, "min");
        String max = string(range, "max");
        return (min == null || compare(version, min) >= 0) && (max == null || compare(version, max) < 0);
    }

    /** Compares dotted version numbers part by part, as numbers. */
    static int compare(String a, String b) {
        String[] left = a.split("[^0-9]+");
        String[] right = b.split("[^0-9]+");

        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            long l = i < left.length && !left[i].isEmpty() ? Long.parseLong(left[i]) : 0;
            long r = i < right.length && !right[i].isEmpty() ? Long.parseLong(right[i]) : 0;

            if (l != r) {
                return Long.compare(l, r);
            }
        }

        return 0;
    }

    static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
