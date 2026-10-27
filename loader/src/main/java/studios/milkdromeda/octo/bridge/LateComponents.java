package studios.milkdromeda.octo.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The components a registry entry will have, before they are bound — for
 * {@link studios.milkdromeda.octo.transform.UnboundComponents}.
 *
 * <p>The view reads through to the holder: empty while its components are
 * unbound, theirs from the moment they are. A stack made from it during
 * registration takes it as its prototype, so once the server has loaded its
 * data the stack is the stack it would have been.
 */
public final class LateComponents {
    private static final OctoLog LOG = OctoLog.of(LateComponents.class);
    private static final Map<Object, Object> VIEWS = new WeakHashMap<>();
    private static volatile Field bound;

    private LateComponents() {
    }

    /** Called by {@code Holder.Reference.components()}, with the components it has bound or {@code null}. */
    public static Object components(Object holder, Object components) {
        if (components != null || holder == null) {
            return components;
        }

        synchronized (VIEWS) {
            Object view = VIEWS.get(holder);

            if (view == null) {
                view = view(holder);

                if (view == null) {
                    throw new NullPointerException("Components not bound yet");
                }

                VIEWS.put(holder, view);
            }

            return view;
        }
    }

    private static Object view(Object holder) {
        try {
            Class<?> map = Class.forName("net.minecraft.core.component.DataComponentMap", false,
                    holder.getClass().getClassLoader());
            InvocationHandler handler = (proxy, method, arguments) -> answer(holder, proxy, method, arguments);
            return Proxy.newProxyInstance(map.getClassLoader(), new Class<?>[] { map }, handler);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("no view of unbound components: {}", e.toString());
            return null;
        }
    }

    private static Object answer(Object holder, Object proxy, Method method, Object[] arguments) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> "components of " + holder + (bound(holder) == null ? ", not bound yet" : "");
            };
        }

        Object real = bound(holder);

        if (real != null) {
            try {
                return method.invoke(real, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        // Not bound yet: nothing in it. The interface's own defaults are
        // built on these two.
        return switch (method.getName()) {
            case "keySet" -> Set.of();
            case "get" -> null;
            default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, arguments)
                    : method.getReturnType() == boolean.class ? false
                    : method.getReturnType() == int.class ? 0 : null;
        };
    }

    private static Object bound(Object holder) throws IllegalAccessException {
        Field field = bound;

        for (Class<?> type = holder.getClass(); field == null && type != null; type = type.getSuperclass()) {
            try {
                field = type.getDeclaredField("components");
                field.setAccessible(true);
                bound = field;
            } catch (NoSuchFieldException e) {
                // Declared further up.
            }
        }

        if (field == null) {
            return null;
        }

        return field.get(holder);
    }
}
