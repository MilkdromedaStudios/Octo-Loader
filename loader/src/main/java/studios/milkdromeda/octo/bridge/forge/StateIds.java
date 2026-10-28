package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Method;
import java.util.Map;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Numbers the block and fluid states of blocks and fluids mods registered,
 * as the game numbers its own.
 *
 * <p>The game gives every block state an id, which is how a chunk or a block
 * change is sent to a client, and builds each state's cache, once, while it
 * bootstraps. NeoForge's patched game does it again after mods register; the
 * unpatched game never does, so none of Create's block states had an id, and
 * the first change to one disconnected the player: the server could not
 * write it. Each block item is also given its block, so a mod's block knows
 * its item.
 */
public final class StateIds {
    private static final OctoLog LOG = OctoLog.of(StateIds.class);

    private StateIds() {
    }

    /** Numbers whatever states are not numbered yet; those already numbered keep their ids. */
    public static void assign(ClassLoader loader) {
        try {
            Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader);
            Class<?> blockType = Class.forName("net.minecraft.world.level.block.Block", true, loader);
            Class<?> fluidType = Class.forName("net.minecraft.world.level.material.Fluid", true, loader);
            Class<?> itemType = Class.forName("net.minecraft.world.item.Item", true, loader);
            Class<?> blockItem = Class.forName("net.minecraft.world.item.BlockItem", true, loader);

            int blocks = number(builtIn.getField("BLOCK").get(null), blockType.getField("BLOCK_STATE_REGISTRY").get(null),
                    true);
            int fluids = number(builtIn.getField("FLUID").get(null), fluidType.getField("FLUID_STATE_REGISTRY").get(null),
                    false);

            @SuppressWarnings("unchecked")
            Map<Object, Object> byBlock = (Map<Object, Object>) itemType.getField("BY_BLOCK").get(null);
            Method registerBlocks = blockItem.getMethod("registerBlocks", Map.class, itemType);
            Method getBlock = blockItem.getMethod("getBlock");
            int items = 0;

            for (Object item : (Iterable<?>) builtIn.getField("ITEM").get(null)) {
                if (blockItem.isInstance(item) && !byBlock.containsKey(getBlock.invoke(item))) {
                    registerBlocks.invoke(item, byBlock, item);
                    items++;
                }
            }

            items += refreshCachedItems(builtIn.getField("BLOCK").get(null), blockType, byBlock);

            if (blocks + fluids + items > 0) {
                LOG.info("numbered {} block state(s) and {} fluid state(s) mods added, and linked {} block item(s)",
                        blocks, fluids, items);
            }
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("mods' block states could not be numbered; changing one of their blocks may disconnect players: {}",
                    Failures.describe(Failures.unwrap(e)));
        }
    }

    /**
     * A block remembers its item the first time it is asked, and a mod that
     * asked while registering, before its items were linked, left its block
     * remembering air: Supplementaries' jar then cast air to its jar item and
     * every jar failed to load its contents. Each block now remembers the
     * item it actually has.
     *
     * @return how many blocks had remembered the wrong one
     */
    private static int refreshCachedItems(Object blocks, Class<?> blockType, Map<Object, Object> byBlock)
            throws ReflectiveOperationException {
        java.lang.reflect.Field cached;

        try {
            cached = blockType.getDeclaredField("item");
            cached.setAccessible(true);
        } catch (NoSuchFieldException e) {
            return 0;
        }

        int fixed = 0;

        for (Object block : (Iterable<?>) blocks) {
            Object item = byBlock.get(block);
            Object remembered = cached.get(block);

            if (item != null && remembered != null && remembered != item) {
                cached.set(block, item);
                fixed++;
            }
        }

        return fixed;
    }

    private static int number(Object registry, Object mapper, boolean initCache) throws ReflectiveOperationException {
        Method getId = mapper.getClass().getMethod("getId", Object.class);
        Method add = mapper.getClass().getMethod("add", Object.class);
        int added = 0;

        for (Object entry : (Iterable<?>) registry) {
            Object definition = entry.getClass().getMethod("getStateDefinition").invoke(entry);

            for (Object state : (Iterable<?>) definition.getClass().getMethod("getPossibleStates").invoke(definition)) {
                if ((int) getId.invoke(mapper, state) != -1) {
                    continue;
                }

                add.invoke(mapper, state);
                added++;

                if (initCache) {
                    try {
                        state.getClass().getMethod("initCache").invoke(state);
                    } catch (Throwable e) {
                        Failures.rethrowIfFatal(e);
                        LOG.debug("{} could not build its cache: {}", state, e.toString());
                    }
                }
            }
        }

        return added;
    }
}
