package studios.milkdromeda.octo.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The call a block got each time an entity's movement met it from above or
 * below, which 26.x no longer makes.
 *
 * <p>Until 1.21.1 {@code Block.updateEntityAfterFallOn(getter, entity)} —
 * {@code updateEntityMovementAfterFallOn} from 1.21.2 — ran on the block under
 * an entity whenever its vertical movement was stopped: every tick for an item
 * lying on it. 26.x keeps only {@code bounceOn}, and only for blocks that
 * bounce. Create's millstone and basin take the items dropped on them there,
 * and on 26.x took nothing at all.
 *
 * <p>So the game's {@code Entity.move} calls {@link #landed} as the old game
 * did, and a block whose class still declares the old method has it run. A
 * block that does not costs one cached look-up.
 */
public final class BlockLandings {
    private static final OctoLog LOG = OctoLog.of(BlockLandings.class);
    private static final MethodHandle NONE = MethodHandles.constant(Object.class, null);

    /** Per block class, its old landing method, or {@link #NONE}. */
    private static final ClassValue<MethodHandle> OLD_HOOK = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            for (Class<?> current = type; current != null && !isGame(current); current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if ((method.getName().equals("updateEntityMovementAfterFallOn")
                            || method.getName().equals("updateEntityAfterFallOn"))
                            && method.getParameterCount() == 2 && !Modifier.isStatic(method.getModifiers())
                            && method.getReturnType() == void.class) {
                        try {
                            method.setAccessible(true);
                            return MethodHandles.lookup().unreflect(method);
                        } catch (Throwable e) {
                            Failures.rethrowIfFatal(e);
                            LOG.debug("{}'s landing hook cannot be called: {}", type.getName(), e.toString());
                        }
                    }
                }
            }

            return NONE;
        }
    };

    private BlockLandings() {
    }

    private static volatile MethodHandle[] access;

    /**
     * Called from {@code Entity.move} as it returns: after a vertical
     * collision, the block under the entity is told.
     */
    public static void afterMove(Object entity, boolean verticalCollision) {
        if (!verticalCollision || entity == null) {
            return;
        }

        try {
            MethodHandle[] handles = access;

            if (handles == null) {
                handles = access = handles(entity.getClass());
            }

            Object level = handles[0].invoke(entity);
            Object state = handles[2].invoke(level, handles[1].invoke(entity));
            landed(handles[3].invoke(state), level, entity);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            studios.milkdromeda.octo.transform.GapBridge.contain(e, "Entity.move (landing)");
        }
    }

    /** {@code Entity.level()}, {@code Entity.getOnPosLegacy()}, {@code Level.getBlockState(pos)}, {@code BlockState.getBlock()}. */
    private static MethodHandle[] handles(Class<?> type) throws ReflectiveOperationException {
        ClassLoader loader = type.getClassLoader();
        Class<?> entity = Class.forName("net.minecraft.world.entity.Entity", false, loader);
        Class<?> level = Class.forName("net.minecraft.world.level.Level", false, loader);
        Class<?> pos = Class.forName("net.minecraft.core.BlockPos", false, loader);
        Class<?> state = Class.forName("net.minecraft.world.level.block.state.BlockState", false, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        return new MethodHandle[] {
                lookup.unreflect(entity.getMethod("level")),
                lookup.unreflect(entity.getMethod("getOnPosLegacy")),
                lookup.unreflect(level.getMethod("getBlockState", pos)),
                lookup.unreflect(state.getMethod("getBlock")) };
    }

    /** Called from {@code Entity.move} after a vertical collision: the block under the entity is told. */
    public static void landed(Object block, Object level, Object entity) {
        if (block == null) {
            return;
        }

        MethodHandle hook = OLD_HOOK.get(block.getClass());

        if (hook == NONE) {
            return;
        }

        try {
            hook.invoke(block, level, entity);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            studios.milkdromeda.octo.transform.GapBridge.contain(e,
                    block.getClass().getName() + ".updateEntityAfterFallOn");
        }
    }

    private static boolean isGame(Class<?> type) {
        String name = type.getName();
        return name.startsWith("net.minecraft.") || name.startsWith("com.mojang.") || name.equals("java.lang.Object");
    }
}
