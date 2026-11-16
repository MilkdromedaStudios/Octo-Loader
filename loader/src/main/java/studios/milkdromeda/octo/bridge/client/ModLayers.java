package studios.milkdromeda.octo.bridge.client;

import java.lang.reflect.Method;
import java.util.Map;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The model layers mods define, added to the game's.
 *
 * <p>A mob's model is built from a layer definition the game looks up by
 * name, and the loaders' patched game adds the ones mods registered to its
 * own as it builds the table. Unpatched, the table had only the game's, and
 * every mod renderer asking for its layer failed to be made at all.
 */
public final class ModLayers {
    private static final OctoLog LOG = OctoLog.of(ModLayers.class);

    /** The loaders' own collections of what mods registered, each emptied into a map builder. */
    private static final String[] HOOKS = { "net.minecraftforge.client.ForgeHooksClient",
            "net.neoforged.neoforge.client.ClientHooks" };

    private ModLayers() {
    }

    /** Called as {@code LayerDefinitions.createRoots} returns: the game's layers, and the mods'. */
    public static Map<?, ?> withMods(Map<?, ?> roots) {
        try {
            ClassLoader loader = roots.getClass().getClassLoader();
            Class<?> builderType = Class.forName("com.google.common.collect.ImmutableMap$Builder", true, loader);
            Object builder = Class.forName("com.google.common.collect.ImmutableMap", true, loader)
                    .getMethod("builder").invoke(null);
            builderType.getMethod("putAll", Map.class).invoke(builder, roots);
            boolean any = false;

            for (String hooks : HOOKS) {
                try {
                    Class<?> type = Class.forName(hooks, true, loader);
                    Method put = builderType.getMethod("put", Object.class, Object.class);

                    // One at a time where the loader's own table can be read:
                    // a layer whose definition cannot be made here costs that
                    // layer, where through the loader's method it cost them all.
                    try {
                        java.lang.reflect.Field table = type.getDeclaredField("layerDefinitions");
                        table.setAccessible(true);

                        for (Map.Entry<?, ?> entry : ((Map<?, ?>) table.get(null)).entrySet()) {
                            try {
                                Object definition = ((java.util.function.Supplier<?>) entry.getValue()).get();

                                if (definition == null) {
                                    throw new IllegalStateException("its supplier made nothing");
                                }

                                put.invoke(builder, entry.getKey(), definition);
                            } catch (Throwable e) {
                                Failures.rethrowIfFatal(e);
                                LOG.warn("the model layer {} could not be made: {}", entry.getKey(),
                                        Failures.describe(Failures.unwrap(e)));
                            }
                        }
                    } catch (NoSuchFieldException e) {
                        type.getMethod("loadLayerDefinitions", builderType).invoke(null, builder);
                    }

                    any = true;
                } catch (ClassNotFoundException | NoSuchMethodException e) {
                    // That loader's API is not here.
                } catch (Throwable e) {
                    Failures.rethrowIfFatal(e);
                    LOG.warn("the model layers mods registered with {} could not be added: {}", hooks,
                            Failures.describe(Failures.unwrap(e)));
                }
            }

            if (!any) {
                return roots;
            }

            Map<?, ?> all = (Map<?, ?>) builderType.getMethod("buildKeepingLast").invoke(builder);

            if (all.size() > roots.size()) {
                LOG.info("{} model layer(s) from mods added to the game's", all.size() - roots.size());
            }

            return all;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("mods' model layers could not be added: {}", Failures.describe(Failures.unwrap(e)));
            return roots;
        }
    }
}
