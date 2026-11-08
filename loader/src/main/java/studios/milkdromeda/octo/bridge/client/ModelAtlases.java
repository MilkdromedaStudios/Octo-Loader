package studios.milkdromeda.octo.bridge.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Item textures where mods from before 26.x expect them: in the block atlas.
 *
 * <p>Until 26.x the game stitched {@code textures/item/} into the same atlas
 * as {@code textures/block/}, and a block model could use either. Mods drew
 * on that: Create's goggles on a player's head and its packages are block
 * models with item textures. 26.x keeps items in an atlas of their own and
 * rejects a block model that uses one, so those parts drew as nothing.
 *
 * <p>For the namespaces of mods from an older Minecraft, their item textures
 * are stitched into the block atlas instead of the item atlas. The game looks
 * a model's texture up in the item atlas first and the block atlas next, so
 * their item models still find them, now in the atlas their block models need.
 */
public final class ModelAtlases {
    private static final OctoLog LOG = OctoLog.of(ModelAtlases.class);

    private static volatile Set<String> legacy = Set.of();

    private ModelAtlases() {
    }

    /** The namespaces whose item textures go in the block atlas. */
    public static void legacyNamespaces(Collection<String> namespaces) {
        Set<String> out = new TreeSet<>();

        for (String namespace : namespaces) {
            if (namespace != null && !namespace.isBlank() && !namespace.equals("minecraft")) {
                out.add(namespace);
            }
        }

        legacy = Set.copyOf(out);

        if (!out.isEmpty()) {
            LOG.info("item textures stitched with blocks, as before 26.x, for: {}", out);
        }
    }

    static Set<String> legacyNamespaces() {
        return legacy;
    }

    /** A regular expression matching exactly these namespaces. */
    static String namespacePattern(Set<String> namespaces) {
        return namespaces.stream().map(Pattern::quote).collect(Collectors.joining("|", "^(?:", ")$"));
    }

    /** A regular expression matching any namespace but these. */
    static String otherNamespacePattern(Set<String> namespaces) {
        return namespaces.stream().map(Pattern::quote).collect(Collectors.joining("|", "^(?!(?:", ")$).*$"));
    }

    /**
     * Called with each atlas's list of sprite sources as
     * {@code SpriteSourceList.load} returns it: the block atlas's gains the
     * legacy namespaces' item textures, and the item atlas's loses them.
     */
    public static Object withLegacyItems(Object list, Object atlas) {
        Set<String> namespaces = legacy;

        if (list == null || atlas == null || namespaces.isEmpty()) {
            return list;
        }

        try {
            String id = atlas.toString();
            boolean blocks = id.equals("minecraft:blocks");
            boolean items = id.equals("minecraft:items");

            if (!blocks && !items) {
                return list;
            }

            ClassLoader loader = list.getClass().getClassLoader();
            Class<?> listType = list.getClass();
            Field sourcesField = listType.getDeclaredField("sources");
            sourcesField.setAccessible(true);
            List<Object> sources = new ArrayList<>((List<?>) sourcesField.get(list));

            if (blocks) {
                // First, so the filter after it sees only these: every item
                // texture, less those of namespaces that are not legacy.
                Object directory = Class
                        .forName("net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister", true, loader)
                        .getConstructor(String.class, String.class).newInstance("item", "item/");
                sources.add(0, directory);
                sources.add(1, filter(loader, otherNamespacePattern(namespaces), "^item/.*$"));
            } else {
                sources.add(filter(loader, namespacePattern(namespaces), "^item/.*$"));
            }

            Constructor<?> make = listType.getDeclaredConstructor(List.class);
            make.setAccessible(true);
            return make.newInstance(sources);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("older mods' item textures stay out of the block atlas: {}",
                    Failures.describe(Failures.unwrap(e)));
            return list;
        }
    }

    private static Object filter(ClassLoader loader, String namespace, String path) throws ReflectiveOperationException {
        Class<?> patternType = Class.forName("net.minecraft.util.IdentifierPattern", true, loader);
        Constructor<?> pattern = patternType.getDeclaredConstructor(Optional.class, Optional.class);
        pattern.setAccessible(true);
        Object matching = pattern.newInstance(Optional.of(Pattern.compile(namespace)),
                Optional.of(Pattern.compile(path)));
        return Class.forName("net.minecraft.client.renderer.texture.atlas.sources.SourceFilter", true, loader)
                .getConstructor(patternType).newInstance(matching);
    }
}
