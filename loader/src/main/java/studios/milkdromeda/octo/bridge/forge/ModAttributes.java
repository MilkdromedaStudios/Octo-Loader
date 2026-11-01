package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * The attributes NeoForge mods give their mobs, read where the game reads
 * every mob's: {@code DefaultAttributes}.
 *
 * <p>A mod hands its mob's attributes to {@code EntityAttributeCreationEvent},
 * which NeoForge keeps in {@code CommonHooks}; NeoForge's patch to
 * {@code DefaultAttributes} looks there after the game's own table. On this
 * game nothing did, so every mod's mob had no attributes — no health, no
 * speed — and the game reported each one and could not spawn it.
 */
public final class ModAttributes {
    private static final String HOOKS = "net.neoforged.neoforge.common.CommonHooks";

    private static volatile Method view;
    private static volatile boolean absent;

    private ModAttributes() {
    }

    /** The attributes a mod gave this entity type, or {@code null}. */
    public static Object supplierFor(Object entityType) {
        Map<?, ?> attributes = attributes(entityType);
        return attributes == null ? null : attributes.get(entityType);
    }

    public static boolean has(Object entityType) {
        return supplierFor(entityType) != null;
    }

    private static Map<?, ?> attributes(Object entityType) {
        if (absent || entityType == null) {
            return null;
        }

        try {
            Method method = view;

            if (method == null) {
                method = Class.forName(HOOKS, true, entityType.getClass().getClassLoader())
                        .getMethod("getAttributesView");
                view = method;
            }

            return (Map<?, ?>) method.invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // No NeoForge here: no mod attributes to look at.
            absent = true;
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
