package studios.milkdromeda.octo.bridge.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.util.OctoLog;

/** Entities skipped for having no renderer, each type named once ({@code EntityRendererGuard}). */
public final class MissingRenderers {
    private static final OctoLog LOG = OctoLog.of(MissingRenderers.class);
    private static final Set<Object> REPORTED = ConcurrentHashMap.newKeySet();

    private MissingRenderers() {
    }

    public static void report(Object entity) {
        Object type;

        try {
            type = entity.getClass().getMethod("getType").invoke(entity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            type = entity.getClass();
        }

        if (REPORTED.add(type)) {
            LOG.warn("{} ({}) has no renderer, so it is not drawn", type, entity.getClass().getName());
        }
    }
}
