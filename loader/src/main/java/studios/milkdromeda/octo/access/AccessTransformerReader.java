package studios.milkdromeda.octo.access;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import studios.milkdromeda.octo.compat.mapping.MappingSet;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Reads Forge's and NeoForge's {@code accesstransformer.cfg}.
 *
 * <pre>
 * public net.minecraft.client.Minecraft player          # one field
 * public net.minecraft.world.item.Item &lt;init&gt;(II)V      # one constructor
 * public-f net.minecraft.world.level.Level              # the class, minus final
 * public net.minecraft.server.MinecraftServer *()       # every method
 * public net.minecraft.server.MinecraftServer *         # every field
 * </pre>
 *
 * <p>Same job as a Fabric access widener, different spelling. Reading both into
 * one table is what lets a Forge mod's transformer open a class that a Fabric
 * mod then reaches into.
 */
public final class AccessTransformerReader {
    private static final OctoLog LOG = OctoLog.of(AccessTransformerReader.class);

    private AccessTransformerReader() {
    }

    public static AccessRules read(byte[] bytes, String origin) {
        return read(new String(bytes, StandardCharsets.UTF_8), origin, MappingSet.EMPTY);
    }

    public static AccessRules read(String text, String origin, MappingSet mappings) {
        AccessRules.Builder rules = AccessRules.builder();
        MappingSet route = mappings == null ? MappingSet.EMPTY : mappings;
        int lineNumber = 0;

        for (String rawLine : text.split("\r?\n")) {
            lineNumber++;
            String line = strip(rawLine);

            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\s+");

            try {
                apply(rules, parts, route);
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
        if (parts.length < 2) {
            throw new IllegalArgumentException("expected <modifier> <class> [<member>]");
        }

        Access access = accessOf(parts[0]);
        String declared = parts[1].replace('.', '/');
        String owner = route.mapClass(declared);

        if (parts.length == 2) {
            rules.widenClass(owner, access);
            return;
        }

        String member = parts[2];

        if (member.equals("*")) {
            rules.widenAllFields(owner, access);
            return;
        }

        if (member.equals("*()")) {
            rules.widenAllMethods(owner, access);
            return;
        }

        int parenthesis = member.indexOf('(');

        if (parenthesis < 0) {
            String name = route.mapField(declared, member, null);
            rules.widenField(owner, name, null, access);
            return;
        }

        String name = member.substring(0, parenthesis);
        String descriptor = member.substring(parenthesis);
        rules.widenMethod(owner, route.mapMethod(declared, name, descriptor),
                route.remapper().mapMethodDesc(descriptor), access);
    }

    /**
     * {@code public}, {@code protected}, {@code default} or {@code private},
     * each optionally suffixed {@code -f} to drop {@code final} or {@code +f} to
     * add it. Only the widening half is honoured: lowering visibility or adding
     * {@code final} on behalf of one mod would break every other mod that was
     * compiled against the class as it really is.
     */
    private static Access accessOf(String modifier) {
        String text = modifier.toLowerCase(Locale.ROOT);
        boolean removeFinal = text.endsWith("-f");
        boolean hasFinalFlag = removeFinal || text.endsWith("+f");
        String visibility = hasFinalFlag ? text.substring(0, text.length() - 2) : text;

        Access base = switch (visibility) {
            case "public" -> Access.PUBLIC;
            case "protected" -> Access.PROTECTED;
            case "default", "private" -> Access.NONE;
            default -> throw new IllegalArgumentException("unknown modifier " + modifier);
        };

        return new Access(base.makePublic(), base.makeProtected(), removeFinal, false);
    }

    private static String strip(String line) {
        int comment = line.indexOf('#');
        return (comment < 0 ? line : line.substring(0, comment)).trim();
    }
}
