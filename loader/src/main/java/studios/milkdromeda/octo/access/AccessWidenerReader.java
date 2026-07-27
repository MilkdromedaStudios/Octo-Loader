package studios.milkdromeda.octo.access;

import java.nio.charset.StandardCharsets;

import studios.milkdromeda.octo.compat.mapping.MappingRegistry;
import studios.milkdromeda.octo.compat.mapping.MappingSet;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Reads Fabric's and Quilt's {@code .accesswidener} files.
 *
 * <pre>
 * accessWidener v2 named
 * accessible   method  net/minecraft/client/Minecraft  getInstance  ()Lnet/minecraft/client/Minecraft;
 * extendable   class   net/minecraft/world/item/Item
 * mutable      field   net/minecraft/world/item/Item   maxStackSize  I
 * </pre>
 *
 * <p>The header names the namespace the file was written in. When the game is
 * running in a different one and a mapping route exists, every name is
 * translated on the way in — a widener written against intermediary names still
 * finds the right members on a Mojang-named runtime.
 *
 * <p>{@code transitive-} prefixes are accepted and mean nothing here: they
 * control what a mod's <em>dependents</em> may compile against, which is a
 * build-time distinction. At run time there is one game and one set of flags.
 */
public final class AccessWidenerReader {
    private static final OctoLog LOG = OctoLog.of(AccessWidenerReader.class);

    private AccessWidenerReader() {
    }

    public static AccessRules read(byte[] bytes, String origin) {
        return read(new String(bytes, StandardCharsets.UTF_8), origin, null);
    }

    /** @param mappings namespace translation, or {@code null} to take it from the header */
    public static AccessRules read(String text, String origin, MappingSet mappings) {
        AccessRules.Builder rules = AccessRules.builder();
        MappingSet route = mappings;
        boolean sawHeader = false;
        int lineNumber = 0;

        for (String rawLine : text.split("\r?\n")) {
            lineNumber++;
            String line = strip(rawLine);

            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("[\\s\\t]+");

            if (!sawHeader) {
                if (!parts[0].equalsIgnoreCase("accessWidener")) {
                    LOG.warn("{} does not start with an accessWidener header; ignoring it", origin);
                    return AccessRules.EMPTY;
                }

                sawHeader = true;

                if (route == null) {
                    route = routeFrom(parts.length > 2 ? parts[2] : null);
                }

                continue;
            }

            try {
                apply(rules, parts, route == null ? MappingSet.EMPTY : route);
            } catch (RuntimeException e) {
                LOG.warn("{}:{}: could not read \"{}\": {}", origin, lineNumber, line, e.toString());
            }
        }

        AccessRules built = rules.build();

        if (!built.isEmpty()) {
            LOG.debug("{}: {}", origin, built);
        }

        return built;
    }

    private static void apply(AccessRules.Builder rules, String[] parts, MappingSet route) {
        if (parts.length < 3) {
            throw new IllegalArgumentException("expected <access> <type> <name...>");
        }

        String verb = parts[0].toLowerCase(java.util.Locale.ROOT);

        if (verb.startsWith("transitive-")) {
            verb = verb.substring("transitive-".length());
        }

        String type = parts[1].toLowerCase(java.util.Locale.ROOT);
        String owner = route.mapClass(parts[2].replace('.', '/'));

        switch (type) {
            case "class" -> rules.widenClass(owner, classAccess(verb));
            case "method" -> {
                require(parts.length >= 5, "method needs an owner, a name and a descriptor");
                String descriptor = route.remapper().mapMethodDesc(parts[4]);
                String name = route.mapMethod(parts[2].replace('.', '/'), parts[3], parts[4]);
                rules.widenMethod(owner, name, descriptor, methodAccess(verb));
            }
            case "field" -> {
                require(parts.length >= 5, "field needs an owner, a name and a descriptor");
                String descriptor = route.remapper().mapDesc(parts[4]);
                String name = route.mapField(parts[2].replace('.', '/'), parts[3], parts[4]);
                rules.widenField(owner, name, descriptor, fieldAccess(verb));
            }
            default -> throw new IllegalArgumentException("unknown target type " + type);
        }
    }

    private static Access classAccess(String verb) {
        return switch (verb) {
            case "accessible" -> Access.PUBLIC;
            // A class you may extend has to be reachable and not final.
            case "extendable" -> new Access(true, false, true, false);
            case "mutable" -> throw new IllegalArgumentException("mutable applies to fields only");
            default -> throw new IllegalArgumentException("unknown access " + verb);
        };
    }

    private static Access methodAccess(String verb) {
        return switch (verb) {
            // Fabric makes a widened private method final, so that opening it up
            // does not silently turn it into something subclasses can override.
            case "accessible" -> new Access(true, false, false, true);
            case "extendable" -> Access.EXTENDABLE;
            default -> throw new IllegalArgumentException("unknown method access " + verb);
        };
    }

    private static Access fieldAccess(String verb) {
        return switch (verb) {
            case "accessible" -> Access.PUBLIC;
            case "mutable" -> Access.MUTABLE;
            default -> throw new IllegalArgumentException("unknown field access " + verb);
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static MappingSet routeFrom(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return MappingSet.EMPTY;
        }

        MappingRegistry registry = MappingRegistry.get();
        return registry.route(namespace, registry.runtimeNamespace());
    }

    private static String strip(String line) {
        int comment = line.indexOf('#');
        return (comment < 0 ? line : line.substring(0, comment)).trim();
    }
}
