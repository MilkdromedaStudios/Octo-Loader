package studios.milkdromeda.octo.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import studios.milkdromeda.octo.mod.EntrypointSpec;

/**
 * Turns an entrypoint declaration into an object.
 *
 * <p>Fabric and Quilt allow three forms — a class to instantiate, a static field
 * holding an instance, and a method reference to adapt — and mods use all three.
 */
public final class Entrypoints {
    private Entrypoints() {
    }

    /**
     * @param expectedType the interface the caller needs, used to adapt a method
     *                     reference; may be {@code null} for a plain class
     */
    public static Object create(EntrypointSpec spec, ClassLoader classLoader, Class<?> expectedType)
            throws ReflectiveOperationException {
        Class<?> owner = Class.forName(spec.className(), false, classLoader);

        if (spec.isConstructor()) {
            return instantiate(owner);
        }

        Field field = findField(owner, spec.memberName());

        if (field != null) {
            field.setAccessible(true);
            return field.get(null);
        }

        Method method = findMethod(owner, spec.memberName());

        if (method == null) {
            throw new NoSuchMethodException(spec.className() + " has no member " + spec.memberName());
        }

        method.setAccessible(true);

        if (expectedType == null || !expectedType.isInterface()) {
            // Nothing to adapt to; hand back a Runnable-shaped call.
            return (Runnable) () -> {
                try {
                    method.invoke(Modifier.isStatic(method.getModifiers()) ? null : instantiate(owner));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("could not invoke " + spec, e);
                }
            };
        }

        MethodHandle handle = MethodHandles.lookup().unreflect(method);

        if (!Modifier.isStatic(method.getModifiers())) {
            handle = handle.bindTo(instantiate(owner));
        }

        return MethodHandleProxies.asInterfaceInstance(expectedType, handle);
    }

    public static Object instantiate(Class<?> type) throws ReflectiveOperationException {
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            return Modifier.isStatic(field.getModifiers()) ? field : null;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String name) {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                return method;
            }
        }

        return null;
    }
}
